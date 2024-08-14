import geopandas as gpd
import pandas as pd

def format_grid_id(x):
    """
    Necessary formating to harmonize the grid and census IDs.
    """
    parts = x.split("N")
    prefix = parts[0]
    coord = parts[1].split("E")
    name = "CRS3035RES" + prefix + "N" + coord[0] + "00" + "E" + coord[1] + "00"
    return name

def format_zensus2022(census_path: str, grid_path: str, nuts_shape_path: str, nuts: list[str]):
    """
    German example for formatting census data for compatibility with omod.
    Due to the size of this data only the specified NUTS area is formated.

    :param census_path:         Path to population file of the german census (zensus2022).
                                File name: Zensus_Bevoelkerung_100m-Gitter.csv
                                Download page: https://www.zensus2022.de/DE/Ergebnisse-des-Zensus/_inhalt.html
    :param grid_path:           Path to the inspire grid geo data (100m cells). Use the file in .gpkg format.
                                Download page: https://gdz.bkg.bund.de/index.php/default/geographische-gitter-fur-deutschland-in-lambert-projektion-geogitter-inspire.html?___SID=U
    :param nuts_shape_path:     Path to NUTS area geodata (.shp).
                                Download page: https://ec.europa.eu/eurostat/web/gisco/geodata/reference-data/administrative-units-statistical-units/nuts
    :param nuts:                List of NUTS area names the census is formated for (e.g. ["DE60", "DE93"])
    """
    # Read admin area shapefile
    admin = gpd.read_file(nuts_shape_path)[["NUTS_ID", "geometry"]]
    mask = admin[admin["NUTS_ID"].isin(nuts)]
    # Read census data
    census = pd.read_csv(census_path, sep=";").set_index("GITTER_ID_100m")
    # Read geo data of census
    grid = gpd.read_file(grid_path, mask=mask)[['id', 'geometry']]
    grid["id"] = grid["id"].apply(format_grid_id)
    grid = grid.set_index("id")
    # Combine information
    grid['population'] = census['Einwohner']
    # Process
    grid["population"] = grid["population"].apply(lambda x: 0 if x < 0 else x).fillna(0)
    # Add type
    grid["type"] = "CensusEntry"
    # Save
    grid.to_crs(4326).to_file(f"Census{'-'.join(nuts)}.geojson", index=False)

if __name__ == "__main__":
    cpath = "Path/To/Census.csv"
    gpath = "Path/To/Grid.gpkg"
    npath = "Path/To/NUTS.shp"
    nutsList = ["DE25", "DE24", "DE23"]
    format_zensus2022(cpath, gpath, npath, nutsList)
