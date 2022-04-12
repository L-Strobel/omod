import pandas as pd
import geopandas as gpd
import sqlalchemy

landuseMap = {
    'residential': 'RESIDENTIAL',
    'commercial': 'COMMERCIAL',
    'retail': 'COMMERCIAL',
    'industrial': 'INDUSTRIAL',
    'military': 'INDUSTRIAL',
    'cemetery': 'RECREATIONAL',
    'meadow': 'RECREATIONAL',
    'grass': 'RECREATIONAL',
    'park': 'RECREATIONAL',
    'recreation_ground': 'RECREATIONAL',
    'allotments': 'RECREATIONAL',
    'scrub': 'RECREATIONAL',
    'heath': 'RECREATIONAL',
    'farmland': 'AGRICULTURE',
    'farmyard': 'AGRICULTURE',
    'orchard': 'AGRICULTURE',
    'forest': 'FOREST',
    'quarry': 'FOREST'
}

def loadBuildingsData(city='München'):
    # OSM-Data
    with sqlalchemy.create_engine('postgresql://postgres:password@localhost/OSM_Ger').connect() as conn:
        # Considered area
        sql = (f"SELECT * FROM planet_osm_polygon WHERE admin_level='6' AND name='{city}'")
        area = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        # Smiliar overpass:
        # [out:csv(::id, ::lat, ::lon, name)];
        # area["name:en"="Munich"]["admin_level"="6"]->.munich;
        # way(area.munich)["building"];
        # out center;
        sql = ("SELECT b.way, b.way_area FROM planet_osm_polygon as a JOIN planet_osm_polygon as b ON ST_Intersects(a.way, ST_Centroid(b.way))"
              f" AND a.admin_level='6' AND a.name='{city}' AND b.building IS NOT NULL")
        buildings = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        # Smiliar overpass:
        # [out:json];
        # area["name:en"="Munich"]["admin_level"="6"]->.by;
        # way(area.by)["landuse"];
        # out geom;
        sql = ("SELECT b.landuse, b.way, b.way_area FROM planet_osm_polygon as a JOIN planet_osm_polygon as b ON ST_Intersects(a.way, b.way)"
              f" AND a.admin_level='6' AND a.name='{city}' AND b.landuse IS NOT NULL")
        landuse = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        # Shops
        sql = ("SELECT b.shop, b.way FROM planet_osm_polygon as a JOIN planet_osm_polygon as b ON ST_Intersects(a.way, b.way)"
               f" AND a.admin_level='6' AND a.name='{city}' AND b.shop IS NOT NULL")
        shopPolygons = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        shopPolygons["way"] = shopPolygons["way"].centroid

        sql = ("SELECT b.shop, b.way FROM planet_osm_polygon as a JOIN planet_osm_point as b ON ST_Intersects(a.way, b.way)"
               f" AND a.admin_level='6' AND a.name='{city}' AND b.shop IS NOT NULL")
        shopPoints = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        shops = pd.concat([shopPoints, shopPolygons], ignore_index=True)

        # Offices
        sql = ("SELECT b.office, b.way FROM planet_osm_polygon as a JOIN planet_osm_polygon as b ON ST_Intersects(a.way, b.way)"
               f" AND a.admin_level='6' AND a.name='{city}' AND b.office IS NOT NULL")
        officePolygons = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        officePolygons["way"] = officePolygons["way"].centroid

        sql = ("SELECT b.office, b.way FROM planet_osm_polygon as a JOIN planet_osm_point as b ON ST_Intersects(a.way, b.way)"
                    f" AND a.admin_level='6' AND a.name='{city}' AND b.office IS NOT NULL")
        officePoints = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        offices = pd.concat([officePoints, officePolygons], ignore_index=True)

        # Amenities. Currently only needed for schools and universities.
        # In the future: Restaurants, doctors, cinema, and more. I.e. OTHER stuff -> needs correlation analysis with MID
        sql = ("SELECT b.amenity, b.way FROM planet_osm_polygon as a JOIN planet_osm_polygon as b ON ST_Intersects(a.way, b.way)"
            " AND a.admin_level='6' AND a.name='München' AND b.amenity IS NOT NULL")
        amenityPolygons = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        amenityPolygons["way"] = amenityPolygons["way"].centroid

        sql = ("SELECT b.amenity, b.way FROM planet_osm_polygon as a JOIN planet_osm_point as b ON ST_Intersects(a.way, b.way)"
           " AND a.admin_level='6' AND a.name='München' AND b.amenity IS NOT NULL")
        amenityPoint = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        amenities = pd.concat([amenityPoint, amenityPolygons], ignore_index=True)

    buildings = buildings.rename(columns={"way_area": "area"})
    buildings['center'] = buildings.way.centroid
    buildings['x'] = buildings.center.x
    buildings['y'] = buildings.center.y

    # Add Zensus data
    path = "C:/Users/strobel/Projekte/esmregio/Daten/Zensus2011/Einwohner/Zensus_Bevoelkerung_100m-Gitter.csv"
    zensus = pd.read_csv(path, sep=";").set_index("Gitter_ID_100m")

    # Add coordinates to zensus data (Uses INSPIRE Grid with 100mx100m resolution)
    usecols = ['OBJECTID', 'id', 'geometry']
    path = "C:/Users/strobel/Projekte/esmregio/Daten/INSPIRE_Grids/100m/geogitter/DE_Grid_ETRS89-LAEA_100m.gpkg"
    inspire100 = gpd.read_file(path, mask=area).to_crs(epsg=3857)
    inspire100 = inspire100[usecols].set_index("id")

    inspire100['population'] = zensus['Einwohner']
    inspire100['population'] = inspire100['population'].apply(lambda x: 0 if x < 0 else x)

    # Distribute cell population evenly to buildings
    buildingsInCell = gpd.sjoin(inspire100, buildings, op='intersects')
    grp = buildingsInCell.groupby(level=0)['population']
    buildingsInCell['Pop'] = grp.mean() / grp.count()
    buildings['population'] = buildingsInCell.groupby('index_right').Pop.sum()

    # Add landuse data
    buildings['landuse'] = gpd.sjoin(landuse, buildings, op='intersects').groupby('index_right').landuse.first()
    buildings["landuse"] = buildings["landuse"].apply(lambda x: landuseMap.get(x, "NONE"))

    # Add shopping locations
    buildings["number_shops"] = gpd.sjoin(buildings, shops, op='contains').groupby(level=0).index_right.count()
    buildings["number_shops"] = buildings["number_shops"].fillna(0).astype(int)

    # Add office locations
    buildings["number_offices"] = gpd.sjoin(buildings, offices, op='contains').groupby(level=0).index_right.count()
    buildings["number_offices"] = buildings["number_offices"].fillna(0).astype(int)

    # Add school locations
    schools = amenities[amenities.amenity == "school"]
    buildings["number_schools"] = gpd.sjoin(buildings, schools, op='contains').groupby(level=0).index_right.count()
    buildings["number_schools"] = buildings["number_schools"].fillna(0).astype(int)

    # Add office locations
    universities = amenities[amenities.amenity == "university"]
    buildings["number_universities"] = gpd.sjoin(buildings, universities, op='contains').groupby(level=0).index_right.count()
    buildings["number_universities"] = buildings["number_universities"].fillna(0).astype(int)

    # Add region type
    regioStaR = pd.read_excel("C:/Users/strobel/Projekte/esmregio/Daten/RegioStaR/regiostar-referenzdateien.xlsx", sheet_name="ReferenzGebietsstand2019")
    regioStaR = regioStaR[["gem_19", "RegioStaR7"]].set_index("gem_19")
    gem = gpd.read_file("C:/Users/strobel/Projekte/esmregio/Daten/AdminGebiete/vg250_ebenen_0101/VG250_GEM.shp").to_crs(3857)
    gem = gem[["AGS", "geometry"]]
    gem["AGS"] = gem["AGS"].astype(int)
    gem = gem.set_index('AGS')
    gem["RegioStaR7"] = regioStaR["RegioStaR7"]
    buildings = gpd.sjoin(buildings, gem, op='intersects').rename(columns={"index_right": "AGS"})
    buildings["RegioStaR7"] = buildings["RegioStaR7"].astype(int)
    buildings = buildings.rename(columns={"RegioStaR7": "region_type_RegioStaR7"})

    return buildings

if __name__ == "__main__":
    df = loadBuildingsData()
    df[["area", "x", "y", "population", "landuse", "region_type_RegioStaR7", "number_shops",
        "number_offices", "number_schools", "number_universities"]].to_csv("../Buildings.csv", index=False)