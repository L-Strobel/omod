import os
import sys

import pandas as pd
import geopandas as gpd
from shapely.ops import unary_union


def format_pendleratlas(commutingDirPath: str, internalCommutingPath: str, admin_shape_path: str):
    """
    German example for formatting commuting data to an OD-Matrix for compatibility with omod.
    Warning! This is very dependent on the formating of the government data in question and bound to break then the formating is changed.

    :param commutingDirPath:        Path the folder that contains the commuting data for every state.
                                    Download page: https://statistik.arbeitsagentur.de/SiteGlobals/Forms/Suche/Einzelheftsuche_Formular.html?nn=15024&r_f=bl_Bayern&topic_f=beschaeftigung-sozbe-krpend
    :param internalCommutingPath:   Path the file that contains the internal commuting numbers.
                                    Download page: https://statistik.arbeitsagentur.de/SiteGlobals/Forms/Suche/Einzelheftsuche_Formular.html?submit=Suchen&topic_f=beschaeftigung-sozbe-gemband
    :param admin_shape_path:        Path to admin area geodata for german counties (VG250_KRS.shp).
                                    Download page: https://gdz.bkg.bund.de/index.php/default/digitale-geodaten/verwaltungsgebiete/verwaltungsgebiete-1-250-000-ebenen-stand-01-01-vg250-ebenen-01-01.html
    """

    # Read data
    ## Commuting data
    df_commuting = []
    for fn in os.listdir(commutingDirPath):
        if fn.endswith(".xlsx"):
            df_commuting.append(pd.read_excel(commutingDirPath + "/" + fn, sheet_name="Auspendler Kreise", skiprows=6, usecols=["Wohnort", "Arbeitsort", "Insgesamt"]))
    df_commuting = pd.concat(df_commuting)

    ## Internal commuting data
    df_internal = pd.read_excel(internalCommutingPath, sheet_name="Gemeindedaten", skiprows=5)
    
    ## Geodata of the admin areas 
    krs = gpd.read_file(admin_shape_path)[["geometry", "AGS"]]
    krs = krs.to_crs(4326)
    krs = krs.groupby("AGS").agg(unary_union)

    # Fix excel formating
    df_commuting = df_commuting.fillna(method="ffill").replace("*", 0)
    df_commuting = df_commuting[df_commuting["Wohnort"].apply(lambda x: len(str(x)) == 5)]
    df_commuting = df_commuting[df_commuting["Arbeitsort"].apply(lambda x: len(str(x)) == 5)]
    df_commuting.reset_index(inplace=True, drop=True)

    df_internal = df_internal.rename(columns={"Unnamed: 0": "AGS", "Unnamed: 1": "Name", "Wohnort\ngleich\nArbeitsort": "InternalCommutes"})[[ "AGS", "Name", "InternalCommutes"]]
    df_internal["AGS"] = df_internal["AGS"].dropna()
    df_internal = df_internal[df_internal.AGS.apply(lambda x: 5 == len(str(x)) or str(x).endswith("000"))]
    df_internal["AGS"] = df_internal["AGS"].apply(lambda x: str(x)[:-3] if str(x).endswith("000") else str(x)).str.zfill(5)
    df_internal = df_internal.set_index("AGS")

    # Add internal commuting
    for county in df_commuting["Arbeitsort"].unique():
        df_commuting.loc[df_commuting.index.max()+1] = [county, county, df_internal.loc[county, "InternalCommutes"]]

    # Create od
    od = df_commuting.pivot(index=["Wohnort"], columns=["Arbeitsort"], values=["Insgesamt"])
    od.fillna(0, inplace=True)

    # Format od
    od.columns = od.columns.droplevel(0)
    od = od.rename_axis("Origin").rename_axis("Destination", axis=1)
    od["geometry"] = krs.geometry
    od.index.name = "origin"

    # Create geojson
    out = od[["geometry"]].copy()
    out["type"] = "ODEntry"
    out["origin_activity"] = "HOME"
    out["destination_activity"] = "WORK"
    out["destinations"] = od.apply(lambda row: {c:row[c] for c in row.index.drop(["geometry"])}, axis=1)
    out = out.set_geometry("geometry")
    out.to_file("OD-Matrix.geojson")

if __name__ == "__main__":
    format_pendleratlas(sys.argv[1], sys.argv[2], sys.argv[3])