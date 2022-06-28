from numpy import double
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

outCrs = 4326

def loadBuildingsData(area_osm_ids: str, buffer_radius: double):
    # OSM-Data
    with sqlalchemy.create_engine('postgresql://postgres:password@localhost/OSM_Ger').connect() as conn:
        # Considered area
        sql = (f"SELECT * FROM planet_osm_polygon WHERE osm_id in ({area_osm_ids})")
        small_area = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        # Buffer area
        center_coords = str(small_area.unary_union.centroid.coords[:][0])
        # Get maximum distance from center of area to boundary
        area_max_radius = small_area.exterior.apply( 
                lambda x: x.hausdorff_distance(small_area.unary_union.centroid)
        ).max()
        large_area_sql = f"ST_Buffer(ST_SetSRID(ST_MakePoint{center_coords}, 3857), {area_max_radius + buffer_radius})"
        sql = (f"SELECT {large_area_sql}")
        large_area = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="st_buffer")
        print("Got areas")

        # Smiliar overpass:
        # [out:csv(::id, ::lat, ::lon, name)];
        # area["name:en"="Munich"]["admin_level"="6"]->.munich;
        # way(area.munich)["building"];
        # out center;
        sql = f"SELECT way, way_area, osm_id FROM planet_osm_polygon WHERE ST_Intersects(way, {large_area_sql}) AND building IS NOT NULL"
        buildings = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        print("Got buildings")

        # Smiliar overpass:
        # [out:json];
        # area["name:en"="Munich"]["admin_level"="6"]->.by;
        # way(area.by)["landuse"];
        # out geom;
        sql = f"SELECT landuse, way, way_area FROM planet_osm_polygon WHERE ST_Intersects(way, {large_area_sql}) AND landuse IS NOT NULL"
        landuse = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        # Shops
        sql = f"SELECT shop, way FROM planet_osm_polygon WHERE ST_Intersects(way, {large_area_sql}) AND shop IS NOT NULL"
        shopPolygons = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        shopPolygons["way"] = shopPolygons["way"].centroid

        sql = f"SELECT shop, way FROM planet_osm_point WHERE ST_Intersects(way, {large_area_sql}) AND shop IS NOT NULL"
        shopPoints = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        shops = pd.concat([shopPoints, shopPolygons], ignore_index=True)

        # Offices
        sql = f"SELECT office, way FROM planet_osm_polygon WHERE ST_Intersects(way, {large_area_sql}) AND office IS NOT NULL"
        officePolygons = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        officePolygons["way"] = officePolygons["way"].centroid

        sql = f"SELECT office, way FROM planet_osm_point WHERE ST_Intersects(way, {large_area_sql}) AND office IS NOT NULL"
        officePoints = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        offices = pd.concat([officePoints, officePolygons], ignore_index=True)

        # Amenities. Currently only needed for schools and universities.
        # In the future: Restaurants, doctors, cinema, and more. I.e. OTHER stuff -> needs correlation analysis with MID
        sql = f"SELECT amenity, way FROM planet_osm_polygon WHERE ST_Intersects(way, {large_area_sql}) AND amenity IS NOT NULL"
        amenityPolygons = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")
        amenityPolygons["way"] = amenityPolygons["way"].centroid

        sql = f"SELECT amenity, way FROM planet_osm_point WHERE ST_Intersects(way, {large_area_sql}) AND amenity IS NOT NULL"
        amenityPoint = gpd.GeoDataFrame.from_postgis(sql, conn, geom_col="way")

        amenities = pd.concat([amenityPoint, amenityPolygons], ignore_index=True)
        print("Got points")

    # Add Zensus data
    path = "C:/Users/strobel/Projekte/esmregio/Daten/Zensus2011/Einwohner/Zensus_Bevoelkerung_100m-Gitter.csv"
    zensus = pd.read_csv(path, sep=";").set_index("Gitter_ID_100m")

    # Add coordinates to zensus data (Uses INSPIRE Grid with 100mx100m resolution)
    usecols = ['OBJECTID', 'id', 'geometry']
    path = "C:/Users/strobel/Projekte/esmregio/Daten/INSPIRE_Grids/100m/geogitter/DE_Grid_ETRS89-LAEA_100m.gpkg"
    inspire100 = gpd.read_file(path, mask=large_area).to_crs(epsg=3857)
    inspire100 = inspire100[usecols].set_index("id")

    inspire100['population'] = zensus['Einwohner']
    inspire100['population'] = inspire100['population'].apply(lambda x: 0 if x < 0 else x)

    # Distribute cell population evenly to buildings
    buildingsInCell = gpd.sjoin(inspire100, buildings, predicate='intersects')
    grp = buildingsInCell.groupby(level=0)['population']
    buildingsInCell['Pop'] = grp.mean() / grp.count()
    buildings['population'] = buildingsInCell.groupby('index_right').Pop.sum()

    # Add landuse data
    buildings['landuse'] = gpd.sjoin(landuse, buildings, predicate='intersects').groupby('index_right').landuse.first()
    buildings["landuse"] = buildings["landuse"].apply(lambda x: landuseMap.get(x, "NONE"))

    # Add shopping locations
    buildings["number_shops"] = gpd.sjoin(buildings, shops, predicate='contains').groupby(level=0).index_right.count()
    buildings["number_shops"] = buildings["number_shops"].fillna(0).astype(int)

    # Add office locations
    buildings["number_offices"] = gpd.sjoin(buildings, offices, predicate='contains').groupby(level=0).index_right.count()
    buildings["number_offices"] = buildings["number_offices"].fillna(0).astype(int)

    # Add school locations
    schools = amenities[amenities.amenity == "school"]
    buildings["number_schools"] = gpd.sjoin(buildings, schools, predicate='contains').groupby(level=0).index_right.count()
    buildings["number_schools"] = buildings["number_schools"].fillna(0).astype(int)

    # Add office locations
    universities = amenities[amenities.amenity == "university"]
    buildings["number_universities"] = gpd.sjoin(buildings, universities, predicate='contains').groupby(level=0).index_right.count()
    buildings["number_universities"] = buildings["number_universities"].fillna(0).astype(int)

    # Add region type
    regioStaR = pd.read_excel("C:/Users/strobel/Projekte/esmregio/Daten/RegioStaR/regiostar-referenzdateien.xlsx", sheet_name="ReferenzGebietsstand2019")
    regioStaR = regioStaR[["gem_19", "RegioStaR7"]].set_index("gem_19")
    gem = gpd.read_file("C:/Users/strobel/Projekte/esmregio/Daten/AdminGebiete/vg250_ebenen_0101/VG250_GEM.shp").to_crs(3857)
    gem = gem[["AGS", "geometry"]]
    gem["AGS"] = gem["AGS"].astype(int)
    gem = gem.set_index('AGS')
    gem["RegioStaR7"] = regioStaR["RegioStaR7"]
    buildings = gpd.sjoin(buildings, gem, predicate='intersects').rename(columns={"index_right": "AGS"})
    buildings["RegioStaR7"] = buildings["RegioStaR7"].astype(int)
    buildings = buildings.rename(columns={"RegioStaR7": "region_type_RegioStaR7"})

    # Mark which building is in smaller area
    buildings["InFocusArea"] = False
    idxs = buildings.clip(small_area, keep_geom_type=True).index
    buildings.loc[idxs, "InFocusArea"] = True

    # Cosmetic and crs
    buildings = buildings.rename(columns={"way_area": "area"})
    buildings['center'] = buildings.way.centroid
    buildings = buildings.set_geometry('center').to_crs(epsg=outCrs)
    buildings['lon'] = buildings.center.x
    buildings['lat'] = buildings.center.y

    return buildings


if __name__ == "__main__":
    #large_area_osm_ids = '-2145268' # Bayern
    # large_area_osm_ids = '-17592' # Oberfranken
    area_osm_ids = '-62640' # Bayreuth
    #area_osm_ids = '-62640,-1070986,-1070976,-1070979,-1071007,-1070996,-1071000,-1070985,-1070974' # EMS-Area
    # osm_ids = '-62428' # München
    df = loadBuildingsData(area_osm_ids, 5000)
    df[["osm_id", "area", "lat", "lon", "population", "landuse", "region_type_RegioStaR7", "number_shops",
        "number_offices", "number_schools", "number_universities", "InFocusArea"]].to_csv(f"../Buildings{area_osm_ids}.csv", index=False)