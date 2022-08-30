import sys

import geopandas as gpd
import pandas as pd


def format_zensus2011(census_path: str, grid_path: str, admin_shape_path: str, rbz: str):
    """
    German example for formatting census data for compatibility with omod.
    Due to the size of this data only the specified government district (rbz) is formated.

    :param census_path:         Path to population file of the german census (zensus2011).
                                Download page: https://www.zensus2011.de/DE/Home/Aktuelles/DemografischeGrunddaten.html
    :param grid_path:           Path to the inspire grid geo data (100m cells)
                                Download page: https://gdz.bkg.bund.de/index.php/default/geographische-gitter-fur-deutschland-in-lambert-projektion-geogitter-inspire.html?___SID=U
    :param admin_shape_path:    Path to admin area geodata for german government districts (VG250_RBZ.shp).
                                Download page: https://gdz.bkg.bund.de/index.php/default/digitale-geodaten/verwaltungsgebiete/verwaltungsgebiete-1-250-000-ebenen-stand-01-01-vg250-ebenen-01-01.html
    :param rbz:                 The government district the census is formated for (e.g. "Unterfranken")
    """
    # Read admin area shapefile
    admin = gpd.read_file(admin_shape_path)[["GEN", "geometry"]]
    mask = admin[admin["GEN"] == rbz]
    # Read census data
    census = pd.read_csv(census_path, sep=";").set_index("Gitter_ID_100m")
    # Read geo data of census
    grid = gpd.read_file(grid_path, mask=mask)[['id', 'geometry']]
    grid = grid.set_index("id")
    # Combine information
    grid['population'] = census['Einwohner']
    # Process
    grid["population"] = grid["population"].apply(lambda x: 0 if x < 0 else x).fillna(0)
    # Add type
    grid["type"] = "CensusEntry"
    # Save
    grid.to_crs(4326).to_file(f"Census{rbz}.geojson", index=False)

if __name__ == "__main__":
    format_zensus2011(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])