import json
import pandas as pd
import geopandas as gpd
from shapely.geometry import Point

def gamgOutputAsDF(fn: str):
    with open(fn, 'r') as f:
        out = json.load(f)
    
    activities = []
    locations = []
    dwellTimes = []
    agentID = []
    for person in out:
        iD = person['id']
        for activity in person['profile']:
            activityType = activity['type']
            loc = Point(activity['x'], activity['y'])
            activities.append(activityType)
            locations.append(loc)
            dwellTimes.append(activity["stayTime"])
            agentID.append(iD)
    df = pd.DataFrame({"AgentID": agentID, "StartLoc": activities, "location": locations, "dwellTimes": dwellTimes})
    return gpd.GeoDataFrame(df, geometry="location", crs=3857)