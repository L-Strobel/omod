import seaborn as sns
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from scipy import stats
from sklearn.mixture import GaussianMixture

# Common positive cont. pdfs. 
distributions = [stats.lognorm, stats.chi, stats.chi2, stats.expon, stats.gamma, stats.weibull_min, stats.rayleigh, stats.pareto] # stats.invgamma <- has numerical problems

# Create models from data
def best_fit_distribution(data):
    """Model data by finding best fit distribution to data"""

    # Best holders
    fittedDists = pd.DataFrame()

    # Estimate distribution parameters from data
    for distribution in distributions:
        # fit dist to data
        params = distribution.fit(data, floc=0)

        # Separate parts of parameters
        arg = params[:-2]
        loc = params[-2]
        scale = params[-1]
               
        fittedDist = {"Name": distribution.name,  "Dist Mean": distribution.mean(loc=loc, scale=scale, *arg), "Real mean": data.mean(),
                      "Dist Var": distribution.var(loc=loc, scale=scale, *arg), "Real Var": data.var(), "Dist Scipy": distribution, 
                      "Parameters": params, "KS-Test": stats.kstest(data, distribution.name, args=params)[0]}
        fittedDists = fittedDists.append(fittedDist, ignore_index=True)
    return fittedDists

# Fit all distributions to each regiontype and return dataframe
def getDistanceDistributions(trips, startLoc, destLoc, plotBest=True):
    df_distributions = pd.DataFrame()
    regions = ["All"] + list(np.sort(trips.RegioStaR7.dropna().unique()))
    
    # Filter dataset
    baseMask = (trips.wegkm < 1000)
    if startLoc is not None:
        baseMask &= (trips.StartLoc == startLoc)
    if destLoc is not None:
        baseMask &= (trips.DestLoc == destLoc)

    if plotBest:
        f, ax = plt.subplots(4, 2, figsize=(10, 5))
        f.tight_layout(pad=3)
        tab10 = plt.get_cmap("tab10")
    
    for i, rType in enumerate(regions):
        if rType != "All":
            mask = baseMask & (trips.RegioStaR7 == rType)
        else:
            mask = baseMask
        data = trips[mask].wegkm.values.squeeze()

        # Find best fit distribution
        df_region = best_fit_distribution(data)
        df_region.loc[:, "RegionType"] = rType
        df_region.loc[:, "Samples"] = len(data)
        df_distributions = df_distributions.append(df_region, ignore_index=True)

        # Plot best fitting distribution
        if plotBest:
            sns.histplot(x="wegkm", binrange=(0, 100), binwidth=1, data=trips[mask], ax=ax.ravel()[i], color = tab10(i), stat="probability")
            row = df_region.sort_values("KS-Test").iloc[0]
            params = row["Parameters"]
            _ = ax.ravel()[i].plot(np.arange(0.1, 100, 0.1), row["Dist Scipy"].pdf(np.arange(0.1, 100, 0.1), loc=params[-2], scale=params[-1], *params[:-2]))
            _ = ax.ravel()[i].set_title(f"{rType}, Best KS-Test: {row['Name']}" )  
            _ = ax.ravel()[i].set_xlim(0, 100)
            _ = ax.ravel()[i].set_xlabel("")
    return df_distributions

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

def addLocations(trips):
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

    startLocs = []
    destLocs = []
    for i in range(len(sos)):
        destLocs.append(getDestByPurp(i))

        startLoc = None
        if wids[i] == 1:
            if sos[i] == 1:
                startLoc = "H"  # Home
            elif sos[i] in [2, 9]:
                startLoc = "O"  # Other
        else:
            startLoc = destLocs[i-1]
        startLocs.append(startLoc)

    trips["StartLoc"] = startLocs
    trips["DestLoc"] = destLocs
    return trips

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

    # Plotting
    # 2D
    if plot and (len(chain) - 1 == 2):
        f, ax = plt.subplots(figsize=(10, 6))

        xx, yy = np.mgrid[0:X[:, 0].max():100j, 0:X[:, 1].max():100j]
        xy_sample = np.vstack([xx.ravel(), yy.ravel()]).T

        density = np.zeros(100*100)
        for i in range(len(model_best.weights_)):
            density += stats.multivariate_normal.pdf(xy_sample, mean=model_best.means_[i], cov=model_best.covariances_[i])
        density = density.reshape(xx.shape)
        ax.contourf(xx, yy, density, levels=30, antialiased=True)
        _ = ax.scatter(X[:, 0], X[:, 1], color="0", s=1)
        _ = ax.set_xlabel(chain[0])
        _ = ax.set_ylabel(chain[1])
    # 3D
    elif plot and (len(chain) - 1 == 3):
        f, ax = plt.subplots(figsize=(10, 6),subplot_kw={'projection':'3d'})

        xxx, yyy, zzz = np.mgrid[0:X[:, 0].max():100j, 0:X[:, 1].max():100j, 0:X[:, 2].max():100j]
        xyz_sample = np.vstack([xxx.ravel(), yyy.ravel(), zzz.ravel()]).T

        density = np.zeros(100*100*100)
        for i in range(len(model_best.weights_)):
            density += stats.multivariate_normal.pdf(xyz_sample, mean=model_best.means_[i], cov=model_best.covariances_[i])
        density = density.reshape(xxx.shape)

        # x-axis
        x_density = np.sum(density, axis=0) / 100
        yy, zz = np.mgrid[0:X[:, 1].max():100j, 0:X[:, 2].max():100j]
        ax.contourf(x_density, yy , zz, levels=20, zdir='x', alpha=0.8, antialiased=True)
        # z-axis
        z_density = np.sum(density, axis=2) / 100
        xx, yy = np.mgrid[0:X[:, 0].max():100j, 0:X[:, 1].max():100j]
        ax.contourf(xx, yy, z_density, levels=20, zdir='z', alpha=0.8, antialiased=True, extend='neither')
        # y-axis
        y_density = np.sum(density, axis=1) / 100
        xx, zz = np.mgrid[0:X[:, 0].max():100j, 0:X[:, 2].max():100j]
        ax.contourf(xx, y_density, zz , levels=20, zdir='y', alpha=0.8, antialiased=True)
        _ = ax.scatter(X[:, 0], X[:, 1], X[:, 2], color="0", alpha=1, s=.2)
        
        _ = ax.set_xlabel(chain[0])
        _ = ax.set_ylabel(chain[1])
        _ = ax.set_zlabel(chain[2])

    return model_best
