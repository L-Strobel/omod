import sys

import geopandas as gpd
import pandas as pd


def format_regiostar(regiostar_path: str, admin_shape_path:str):
    """
    German example for formatting region type data for compatibility with omod.
    Warning! This is very dependent on the formating of the government data in question and bound to break then the formating is changed.

    Types RegioStaR7:
    71 Metropolis
    72 Large city
    73 City
    74 Town in urban region
    75 City in rural region
    76 Town
    77 Rural region

    :param regiostar_path:      Path to regiostar file.
                                Download page: https://www.bmvi.de/SharedDocs/DE/Artikel/G/regionalstatistische-raumtypologie.html
    :param admin_shape_path:    Path to admin area geodata for german municipalities (VG250_GEM.shp).
                                Download page: https://gdz.bkg.bund.de/index.php/default/digitale-geodaten/verwaltungsgebiete/verwaltungsgebiete-1-250-000-ebenen-stand-01-01-vg250-ebenen-01-01.html
    """

    # Read regiostar data
    regioStaR = pd.read_excel(regiostar_path, sheet_name="ReferenzGebietsstand2019", usecols=["gem_19", "RegioStaR7"])
    regioStaR = regioStaR.set_index("gem_19")
    # Read admin area shapefile
    admin = gpd.read_file(admin_shape_path)[["AGS", "geometry"]]
    admin["AGS"] = admin["AGS"].astype(int)
    admin = admin.set_index('AGS')
    # Combine information
    admin["region_type"] = regioStaR["RegioStaR7"]
    # Process
    admin["region_type"] = admin["region_type"].fillna(0).astype(int)
    # Add type
    admin["type"] = "RegionTypeEntry"
    # Save
    admin.to_crs(4326).to_file("region_types.geojson", index=False)

if __name__ == "__main__":
    format_regiostar(sys.argv[1], sys.argv[2])