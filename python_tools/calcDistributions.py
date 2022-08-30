import pandas as pd
import numpy as np
import json
import warnings
import os
from scipy import stats
from sklearn.mixture import GaussianMixture


def loadData(path_mid_persons: str, path_mid_trips: str):    
    """
    Load and process MID data
    :param path_mid_persons:    Path to the "MiD2017_Lokal_Personen.csv" of the MID B3 data set.
    :param path_mid_trips:      Path to the "MiD2017_Lokal_Wege.csv" of the MID B3 data set.
    """

    persons = pd.read_csv(path_mid_persons, sep=";", decimal=",")
    trips = pd.read_csv(path_mid_trips, sep=";", decimal=",")

    # Add region type information
    with open(os.path.join(os.path.dirname(__file__), 'mapID2Regio.json'), 'r', encoding='utf-8') as f:
        regioMap = json.load(f)
    trips["RegioStaR7"] = trips.GITTER_500m.apply(lambda x: regioMap.get(x, None))

    # Drop regular work trips. They have no timing information.
    trips = trips[trips.W_RBW != 1] # pylint: disable=no-member, unsubscriptable-object

    return trips, persons

"""Convert the trip purpose to the activity categories of omod and determine the activity before the trip.
"""
def addActivities(trips):
    # Sort MID
    trips = trips.sort_values(["HP_ID_Lok", "W_ID"])

    sos = trips.W_SO1.values
    wids = trips.W_ID.values
    purposes = trips.zweck.values

    def getDestByPurp(j):
        if purposes[j] in [1]:
            return "W"  # Work
        elif purposes[j] in [2]:
            return "B"  # Business
        elif purposes[j] in [3]:
            return "S" # School 
        elif purposes[j] in [4]:
            return "P" # Shopping
        elif purposes[j] in [5, 6, 7, 10]:
            return "O"
        elif purposes[j] in [8]:
            return "H"
        elif purposes[j] in [10]:
            if wids[j] == 1:
                return None
            else:
                return getDestByPurp(j-1)
        else:
            return None

    activitiesPre = []
    activitiesPost = []
    for i in range(len(sos)):
        activitiesPost.append(getDestByPurp(i))

        activityPre = None
        if wids[i] == 1:
            if sos[i] == 1:
                activityPre = "H"  # Home
            elif sos[i] in [2, 9]:
                activityPre = "O"  # Other
        else:
            activityPre = activitiesPost[i-1]
        activitiesPre.append(activityPre)

    trips["ActivityPre"] = activitiesPre
    trips["ActivityPost"] = activitiesPost
    return trips

def getDistanceDists(trips):
    # Activity combinations with unique distribution
    groups = {"home_work": ("H", "W"), "home_school": ("H", "S"), "any_shopping": (None, "P"), "any_other": (trips, None, "O")}

    # Region types (RegioStaR7) (see: https://www.bmvi.de/SharedDocs/DE/Artikel/G/regionalstatistische-raumtypologie.html)
    # 0 means "undefined"
    regions = [0] + list(np.sort(trips.RegioStaR7.dropna().unique()))

    out = {}
    for name, activities in groups.items():
        # Filter data for correct activities before and after trip
        baseMask = (trips.wegkm < 1000)
        if activities[0] is not None:
            baseMask &= (trips.StartLoc == activities[0])
        if activities[1] is not None:
            baseMask &= (trips.DestLoc == activities[1])
        
        rslt_for_region = {}
        for region in regions:
            # Filter for correct region type
            if region != 0:
                mask = baseMask & (trips.RegioStaR7 == region)
            else:
                mask = baseMask
            
            data = trips[mask].wegkm.values.squeeze() * 1000 # In meters
        
            # Fit lognormal distribution
            params = stats.lognorm.fit(data, floc=0)

            # Store result
            rslt_for_region[int(region)] = {"distribution": "lognorm", "shape": params[-3], "scale": params[-1]}
        out[name] = rslt_for_region
    with open('DistanceDistributions.json', 'w', encoding='utf-8') as json_file:
        json.dump(out, json_file)   

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
    forAllGrps(persons, fun=getChains)
    return groups

# Run some function for each sociodemographic group
def forAllGrps(persons, fun: callable(5)):
    # Weekday
    for wd in list(range(1, 9)) + ["undefined"]:
        if wd == 8:
            wd_mask = (persons.feiertag == 1)
        elif type(wd) == int:
            wd_mask = (persons.ST_WOTAG == wd) & (persons.feiertag == 0)
        elif wd == "undefined":
            wd_mask = pd.Series(True, index = persons.index)
        
        # Homogenous group
        for hom in ["working", "non_working", "pupil_student", "undefined"]:
            if hom == "working":
                hom_mask = (persons.taet == 1)
            elif hom == "non_working":
                hom_mask = (persons.taet.isin([3, 4, 5]))
            elif hom == "pupil_student":
                hom_mask = (persons.taet == 2)
            elif hom == "undefined":
                hom_mask = pd.Series(True, index = persons.index)

            # Mobility group
            for mob in ["car_user", "car_other", "other", "undefined"]:
                if mob == "car_user":
                    mob_mask = (persons.multimodal == 1)
                elif mob == "car_other":
                    mob_mask = (persons.multimodal.isin([4, 5, 6]))
                elif mob == "other":
                    mob_mask = (persons.multimodal.isin([2, 3, 6, 8]))
                elif mob == "undefined":
                    mob_mask = pd.Series(True, index = persons.index)

                # Age
                for age in ["0_40", "40_60", "60_100", "undefined"]:
                    if age == "0_40":
                        age_mask = (persons.alter_gr.isin([1, 2, 3]))
                    elif age == "40_60":
                        age_mask = (persons.alter_gr.isin([4, 5]))
                    elif age == "60_100":
                        age_mask = (persons.alter_gr.isin([6, 7, 8]))
                    elif age == "undefined":
                        age_mask = pd.Series(True, index = persons.index)
                    
                    mask = wd_mask & hom_mask & mob_mask & age_mask
                    ids = persons[mask].HP_ID_Lok

                    fun(wd, hom, mob, age, ids)

def processTime(trips):
    # Get trip count
    W_count = trips.groupby('HP_ID_Lok')['W_ID'].count()
    trips = trips.set_index('HP_ID_Lok')
    trips["W_count"] = W_count
    trips = trips.reset_index()

    # Get starts & stops
    hasStart = (trips.W_SZS < 80) & (trips.W_SZM < 80)
    trips.loc[hasStart, 'start'] = trips[hasStart].W_SZS * 60 + trips[hasStart].W_SZM
    trips.loc[~hasStart, 'start'] = None

    hasStop = (trips.W_AZS < 80) & (trips.W_AZM < 80)
    trips.loc[hasStop, 'stop'] = trips[hasStop].W_AZS * 60 + trips[hasStop].W_AZM + trips[hasStop].W_FOLGETAG * 1440
    trips.loc[~hasStop, 'stop'] = None

    # Impute missing starts & stops
    trips.loc[hasStart & ~hasStop, 'stop'] = trips.loc[hasStart & ~hasStop, 'start'] + trips.loc[hasStart & ~hasStop, 'wegmin_imp1']
    trips.loc[hasStop & ~hasStart, 'start'] = trips.loc[hasStop & ~hasStart, 'stop'] - trips.loc[hasStop & ~hasStart, 'wegmin_imp1']

    # If no information and last trip -> drop trip
    at1 = trips[~hasStart & ~hasStop & (trips.W_ID == trips.W_count)].index
    # Remaining 250 with no info -> drop person
    dropPersons = trips[~hasStart & ~hasStop & (trips.W_ID != trips.W_count)].HP_ID_Lok
    at2 = trips[trips.HP_ID_Lok.isin(dropPersons)].index

    # Drop
    trips = trips.drop(at1.union(at2))
    return trips

def addTime(trips):
    trips = processTime(trips)

    # Sort MID
    trips = trips.sort_values(["HP_ID_Lok", "W_ID"])

    wids = trips.W_ID.values
    starts = trips.start.values
    stops = trips.stop.values

    dwellTime = []
    for i in range(len(starts)):
        if wids[i] == 1:
            dwellTime.append(starts[i])
        else:
            dtime = starts[i]-stops[i-1]
            if dtime < 0:
                dtime = None
            dwellTime.append(dtime)

    trips["DwellTime"] = dwellTime
    return trips

def getGaussianMixture(chain, activityChains, dwellTimes, grpIDs=None, mx_components=20, plot=False):
    ids = activityChains[(activityChains == chain)].index
    if grpIDs is not None:
        ids = ids.intersection(grpIDs)
    X = dwellTimes.loc[ids, :len(chain)-1].dropna().values

    bic_best = None
    model_best = None
    for n in range(1, mx_components):
        model = GaussianMixture(n_components=n).fit(X)
        bic = model.bic(X) # Score
        # BIC increases -> enough components
        if (bic_best is not None) and (bic > bic_best):
            break
        model_best = model
        bic_best = bic
    return model_best

lngNameMap = {"W": "WORK", "B": "BUSINESS", "S": "SCHOOL", "P": "SHOPPING", "O": "OTHER", "H": "HOME"}
def chainStrToList(string):
    rslt = []
    for char in string:
        rslt.append(lngNameMap[char])
    return rslt

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
            model = getGaussianMixture(chain, activityChains, dwellTimes, grpIDs=ids)
            gaussians[(wd, hom, mob, age, chain)] = {"weights": model.weights_.tolist(), "means": model.means_.tolist(), "covariances": model.covariances_.tolist()}
    forAllGrps(persons, getGaus)
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
                g["chain"] = chainStrToList(x)
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
    midTrips, midPersons = loadData()
    midTrips = addActivities(midTrips)

    getDistanceDists(midTrips)

    groupData = getActivityChains(midTrips, midPersons)
    midTrips = addTime(midTrips) # Warning this removes some trips
    gaussianData = getDwellTimeDists(midTrips, midPersons)
    safeActivityGroups(groupData, gaussianData)
