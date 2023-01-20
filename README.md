# OMOD (Open-Street-Maps Mobility Demand Generator)

OMOD is a mobility demand generator that creates synthetic mobility demand based on Open-Street-Maps data
for a user defined location in form of daily activity diaries
in the following format:

```json
[
    {
        "id": 0,
        "homogenousGroup": "undefined",         // Socio-demographic features:
        "mobilityGroup": "undefined",           // Distribution only changeable in code
        "age": "undefined",                     // under src/main/kotlin/resources/Population.json
        "profile": [  // Activities conducted in the simulation time window
            {
                "type": "HOME",                 // Options: HOME, WORK, SCHOOL, SHOPPING, OTHER
                "stayTime": 327.07311000373426, // Time spent at loction. Unit: Minutes
                "lat": 51.14376165369229,       // Latitude
                "lon": 6.649319080237429,       // Longitude
                "dummyLoc": false,              // Placeholder: currently always false
                "inFocusArea": true             // Is the location in the area defined by the GeoJson?
            },
            {
                "type": "OTHER",
                "stayTime": null,               // null means until 00:00
                "lat": 50.95655629837191,
                "lon": 12.399130369888487,
                "dummyLoc": false,
                "inFocusArea": true
            },
            ...
        ]
    },
    ...
]
```

The output describes what each agent did at which location.
It does not say how the agent moved from one location to another.

OMOD can be used for any location on earth.
However, the calibration was conducted using data from the German national household travel survey
(https://www.mobilitaet-in-deutschland.de/publikationen2017.html).
Therefore, the model's performance outside of Germany and especially
in nations with conditions very different to Germany is uncertain.
Additionally, the region must be mapped reasonably well in Open-Street-Maps.

To run OMOD an Open-Street-Maps file of the region and a definition of the region in GeoJson format are necessary.
Additionally, zensus information of the region is helpful
(see python_tools/format_zensus2011.py for an example of how to correctly format zensus data for OMOD).


## Get started

1. Download OSM data of the region you are interested in as an osm.pbf.
Your file can contain more but to large files slow down initialization.
Recommended download side: https://download.geofabrik.de/
2. Get a GeoJson of the region you want to simulate.
This region must be included in the osm.pbf file.
With https://geojson.io you can easily create a geojson of an arbitrary region.
Administrative areas can be obtained quickly with https://polygons.openstreetmap.fr/.
3. Run OMOD:
   ```
   java -jar omod-1.0-all.jar Path/to/GeoJson Path/to/osm.pbf 
   ```

The first run will take some time. Subsequent runs should only take a few minutes.
For optional an explanation of optional parameters run --help.

## Usage as java library

First, add the jar to your classpath.

Basic example:

```java
import de.uniwuerzburg.omod.core.Omod;
import de.uniwuerzburg.omod.core.MobiAgent;
import de.uniwuerzburg.omod.core.Weekday;
import de.uniwuerzburg.omod.core.Activity;
import de.uniwuerzburg.omod.core.ActivityType;

import java.util.LinkedList;

public class App {
    public static void main(String[] args) {
        File areaFile = File("Path/to/GeoJson");
        File osmFile = File("Path/to/osm.pbf");

        // Create a simulator
        Omod omod = Omod.defaultFactory(areaFile, osmFile);

        // Run for 1000 agents, an undefined start day, and 1 day
        List<MobiAgent> agents = omod.run(1000, Weekday.UNDEFINED, 1);

        // Do something with the result. E.g. get activities conducted
        List<ActivityType> activities = new LinkedList<ActivityType>();
        for (MobiAgent agent : agents) {
            for (Activity activity : agent.profile) {
                activities.add( activity.type );
            }
        }
    }
}
```

## Acknowledgement

This model was created as part of the ESM-Regio project (https://www.bayern-innovativ.de/de/seite/esm-regio)
and was made possible through funding of the  	German Federal Ministry for Economic Affairs and Climate Action.