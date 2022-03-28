import pandas as pd
import numpy as np
import midcrypt
from io import StringIO
import json
import utils
import warnings

def loadData():
    persons =  midcrypt.fetchJMU('MiD2017_Lokal_Personen.csv.encrypted')
    persons = pd.read_csv(StringIO(persons), sep=";", decimal=",")

    trips = midcrypt.fetchJMU('MiD2017_Lokal_Wege.csv.encrypted')
    trips = pd.read_csv(StringIO(trips), sep=";", decimal=",")

    with open('mapID2Regio.json', 'r', encoding='utf-8') as f:
        regioMap = json.load(f)
    trips["RegioStaR7"] = trips.GITTER_500m.apply(lambda x: regioMap.get(x, None))

    # Drop regular work trips. They do not appear in order and have no timing information.
    trips = trips[trips.W_RBW != 1] # pylint: disable=no-member,unsubscriptable-object

    # Add start stop location
    trips = utils.addLocations(trips)
    return trips, persons

def getDistanceDists(trips):
    home_work = utils.getDistanceDistributions(trips, "H", "W")
    home_school = utils.getDistanceDistributions(trips, "H", "S")
    any_shopping = utils.getDistanceDistributions(trips, None, "P")
    any_other = utils.getDistanceDistributions(trips, None, "O")

    # Save to json
    groups = {"home_work": home_work, "home_school": home_school, "any_shopping": any_shopping, "any_other": any_other}

    jsn = {}
    for name, df in groups.items():
        tmp = {}
        for region in ["All"] + np.sort(trips.RegioStaR7.dropna().unique()).tolist():
            dist = df[(df.RegionType == region) & (df.Name == "lognorm")].iloc[0]
            region = region if region != "All" else 0
            tmp[int(region)] = {"distribution": dist["Name"], "shape": dist["Parameters"][-3], "scale": dist["Parameters"][-1]}
        jsn[name] = tmp
        with open('DistanceDistributions.json', 'w', encoding='utf-8') as json_file:
            json.dump(jsn, json_file)

def getActivityChains(trips, persons):
    grp = trips.groupby('HP_ID_Lok')
    activityChains = (grp['StartLoc'].sum() + grp['DestLoc'].last())

    # Add not mobil persons
    notMobil = pd.Series("H", index=persons[persons.mobil == 0].HP_ID_Lok.values)
    activityChains = activityChains.append(notMobil)

    groups = []
    def getChains(wd, hom, mob, age, ids):
        ids = activityChains.index.intersection(ids) # Drop ids without info
        grpChains = activityChains[ids].value_counts().to_dict()

        # Create table with sample size per feature group
        groups.append({"weekday": wd, "Hom. Grp": hom, "Mob. Grp": mob, "Age": age, "count": ids.size, "Group chains": grpChains})
    utils.forAllGrps(persons, fun=getChains)
    return groups

def getDwellTimeDists(trips, persons):
    # Memory leak warning in kmeans
    warnings.filterwarnings("ignore", category=UserWarning)

    grp = trips.groupby('HP_ID_Lok')
    activityChains = (grp['StartLoc'].sum() + grp['DestLoc'].last())
    dwellTimes = trips.pivot(index='HP_ID_Lok', columns='W_ID', values='DwellTime')
    
    # ~ 20min
    gaussians = {}
    def getGaus(wd, hom, mob, age, ids):
        # Drop ids without info
        ids = activityChains.index.intersection(ids)
        grpChains = activityChains[ids].value_counts()
        for chain, count in zip(grpChains.index, grpChains.values):
            if count < 30:
                break
            model = utils.getGaussianMixture(chain, activityChains, dwellTimes, grpIDs=ids)
            gaussians[(wd, hom, mob, age, chain)] = {"weights": model.weights_.tolist(), "means": model.means_.tolist(), "covariances": model.covariances_.tolist()}
    utils.forAllGrps(persons, getGaus)
    return gaussians

def safeActivityGroups(groups, gaussians):
    jsn = [] 
    wdMap = {1: 'mo', 2: 'tu', 3: 'we', 4: 'th', 5: 'fr', 6: 'sa', 7: 'so', 8: 'ho', 'undefined': 'undefined'}
    for group in groups:
        # Get chains
        weights = list(group["Group chains"].values())
        chains = list(group["Group chains"].keys())
        activityData = []
        for n, x in sorted(zip(weights, chains), reverse=True):
            # Drop chains with to few samples.
            # Also drop chains that end at work, shopping, business. Very few and can not be resolved sensibly because stay time is unknown.
            if n >= 30 and x[-1] in ["H", "O"]:
                g = {}
                g["chain"] = utils.chainStrToList(x)
                g["weight"] = n               
                if x != "H":
                    g["gaussianMixture"] = gaussians.get((group["weekday"],  group["Hom. Grp"], group["Mob. Grp"],  group["Age"], x), None)
                else:
                    g["gaussianMixture"] = None
                activityData.append(g)
        # Records
        tmp = {}
        tmp["weekday"] = wdMap[group["weekday"]]
        tmp["homogenousGroup"] = group["Hom. Grp"]
        tmp["mobilityGroup"] = group["Mob. Grp"]
        tmp["age"] = group["Age"]
        tmp["sampleSize"] = group["count"]
        tmp["activityChains"] = activityData
        jsn.append(tmp)
    with open('ActivityGroups.json', 'w', encoding='utf-8') as json_file:
        json.dump(jsn, json_file)

if __name__ == "__main__":
    tripData, persData = loadData()
    getDistanceDists(tripData)

    groupData = getActivityChains(tripData, persData)
    tripData = utils.addTime(tripData) # Warning this removes some trips
    gaussianData = getDwellTimeDists(tripData, persData)
    safeActivityGroups(groupData, gaussianData)
