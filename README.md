# OMOD (OpenStreetMap Mobility Demand Generator)

OMOD is a tool that creates synthetic mobility demand based on OpenStreetMap data
for a user-defined location.
The generated demand is in the form of daily activity diaries
in the following format:

```
[
    {
        "id": 0,                             // ID of the agent/person
        "homogenousGroup": "UNDEFINED",      // Socio-demographic features:
        "mobilityGroup": "UNDEFINED",        // Distribution only changeable in code
        "age": "UNDEFINED",                  // under src/main/kotlin/resources/Population.json
        "mobilityDemand": [
            {
                "day": 0,                    // Index of the day
                "dayType": "UNDEFINED",      // Options: MO, TU, WE, TH, FR, SA, SO, HO, UNDEFINED
                "activities": [
                    {
                        "type": "HOME",                  // Options: HOME, WORK, SCHOOL, SHOPPING, OTHER
                        "stayTime": 1133.4945922475808,  // Time spent at location. Unit: Minutes
                        "lat": 53.61571111201344,        // Latitude
                        "lon": 10.107296996943852,       // Longitude
                        "dummyLoc": false,               // Placeholder: currently always false
                        "inFocusArea": true              // Is that location inside the area defined by the GeoJson?
                    },
                    {
                        "type": "OTHER",
                        "stayTime": null,                // null means until 00:00. Always the case for the last activity of a day.
                        "lat": 53.52566194346696,
                        "lon": 9.895169199404203,
                        "dummyLoc": false,
                        "inFocusArea": true
                    },
                    ...
                ]
            },
            ...
        ]
    },
    ...
]
```

The output describes what each agent did at which location.
It does not say how the agent moved from one location to another.

Technically, OMOD is applicable for any location on earth.
However, we calibrated the model using data from the German national household travel survey
(https://www.mobilitaet-in-deutschland.de/publikationen2017.html).
Therefore, the model's performance outside of Germany and especially
in nations with conditions very different to Germany is uncertain.
Additionally, the region must be mapped reasonably well in OpenStreetMap.
Especially important is mapping information about the location and size of buildings, landuse zones,
and the road network.
To run OMOD an OpenStreetMap file of the region and a definition of the region of interest
(in GeoJson format) are necessary.
Additionally, census information of the region is helpful
(see python_tools/format_zensus2011.py for an example of how to correctly format census data for OMOD).

## Get Started

1. Download the latest release of OMOD
2. Download OSM data of the region you are interested in as an osm.pbf.
The file can cover a larger area, but too large files slow down initialization.
Recommended download site: https://download.geofabrik.de/
3. Get a GeoJson of the region you want to simulate.
This region must be covered in the osm.pbf file.
With https://geojson.io, you can easily create a geojson of an arbitrary region.
Geojsons for administrative areas can be obtained quickly with https://polygons.openstreetmap.fr/.
4. Run OMOD:
   ```
   java -jar omod-1.4.1-all.jar Path/to/GeoJson Path/to/osm.pbf 
   ```

Run --help for an explanation of optional parameters, such as the number of agents, weekday, or routing mode.

## Routing Mode
OMOD determines the destination choice of agents based on a gravity model.
The necessary distances from A to B can be calculated with the 
routing mode GraphHopper and Beeline.
The first calculates the distance by car using the open-source router GraphHopper
(https://github.com/graphhopper/graphhopper).
This mode leads to the best result.
However, it also takes significantly longer to compute.
Luckily, most heavy computations can be cached.
Therefore, the first run is slow, but subsequent runs are fast.
The second mode uses the euclidean distance
and is significantly faster but less precise.

Change the routing mode with the CLI flag *--routing_mode* to either *GRAPHHOPPER* or *BEELINE*.

## Usage as Java library

First, add the jar to your classpath.

Basic example:

```java
import de.uniwuerzburg.omod.core.Omod;
import de.uniwuerzburg.omod.core.MobiAgent;
import de.uniwuerzburg.omod.core.Diary;
import de.uniwuerzburg.omod.core.Weekday;
import de.uniwuerzburg.omod.core.Activity;
import de.uniwuerzburg.omod.core.ActivityType;

import java.util.LinkedList;
import java.io.File;
import java.util.List;

class App {
   public static void main (String[] args) {
      File areaFile = new File("Path/to/GeoJson");
      File osmFile = new File("Path/to/osm.pbf");
   
      // Create a simulator
      Omod omod = Omod.Companion.defaultFactory(areaFile, osmFile);
   
      // Run for 1000 agents, an undefined start day, and 1 day
      List<MobiAgent> agents = omod.run(1000, Weekday.UNDEFINED, 1);
   
      // Do something with the result. E.g. get conducted activities 
      List<ActivityType> activities = new LinkedList<ActivityType>();
      for (MobiAgent agent : agents) {
         for (Diary diary : agent.getMobilityDemand()) {
            for (Activity activity : diary.getActivities()) {
                activities.add(activity.getType());
            }
         }
      }
   }
}
```

## Acknowledgment

This model is created as part of the ESM-Regio project (https://www.bayern-innovativ.de/de/seite/esm-regio-en)
and is made possible through funding from the German Federal Ministry for Economic Affairs and Climate Action.
