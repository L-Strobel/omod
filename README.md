# OMOD (OpenStreetMap Mobility Demand Generator)

OMOD is a tool that creates synthetic mobility demand based on OpenStreetMap data
for a user-defined location.
The generated demand describes what an agent *plans* to do on a given day
in the form of daily activity diaries.

Default output format (see [JsonExample.json](doc/outputFormats/JsonExample.json)):

```
{
    "runParameters": {
        ...
    },
    "agents": [
       {
           "id": 0,                          // ID of the agent/person
           "homogenousGroup": "UNDEFINED",   // Options: WORKING, NON_WORKING, PUPIL_STUDENT, UNDEFINED
           "mobilityGroup": "UNDEFINED",     // Options: CAR_USER, CAR_MIXED, NOT_CAR, UNDEFINED
           "age": null,                      // Int?
           "sex": "UNDEFINED",               // Options:  MALE, FEMALE, UNDEFINED
           "carAccess": false,               // Boolean
           "mobilityDemand": [               // Generated mobility demand
               {
                   "day": 0,                 // Zero indexed day number
                   "dayType": "UNDEFINED",   // Options: MO, TU, WE, TH, FR, SA, SU, HO, UNDEFINED; HO = Holiday
                   "plan": [                 // Mobility plan for a given day. Activities - Trip - Activity - ... - Activity
                       {
                           "type": "Activity",                    // Either "Activity" or "Trip"
                           "legID": 0,                            // Index in the daily plan (count continues for both activities and trips)
                           "activityType": "HOME",                // Options: HOME, WORK, SCHOOL, SHOPPING, OTHER
                           "startTime": "00:00",                  
                           "stayTimeMinute": 346.3270257434699,   // Time spent at location. Unit: Minutes. Always Null for the last activity: means "until end of day"
                           "lat": 49.770390415910654,             // Latitude
                           "lon": 9.926447964597246,              // Longitude
                           "dummyLoc": false,                     // Placeholder for calibration: currently always false
                           "inFocusArea": true                    // Is that location inside the area defined by the GeoJson?
                       },
                       {
                           "type": "Trip",
                           "legID": 1,
                           "mode": "BICYCLE",                        // Transport mode of trip. Options: CAR_DRIVER, CAR_PASSENGER, PUBLIC_TRANSIT, BICYCLE, FOOT, UNDEFINED
                           "startTime": "05:46",                  
                           "distanceKilometer": 5.9415048636329315,  // Trip distance. Unit: Kilometer
                           "timeMinute": 21.0,                       // Trip duration. Unit: Minute
                           "lats": [ 49.7704712, 49.7714712, 49.77499469890436 ],  // Trip path coordinates. Only returned when --return_path_coords y 
                           "lons": [ 9.9266363, 9.9264601, 9.874013608000029]
                           ]
                       },
                       {
                           "type": "Activity",
                           "legID": 2,
                           "activityType": "WORK",
                           "startTime": "06:07",
                           "stayTimeMinute": 553.6428611125708,
                           "lat": 49.77492197538944,
                           "lon": 9.873904883397765,
                           "dummyLoc": false,
                           "inFocusArea": false
                       },
                       ...
                   ]
               },
               ...
           ]
       },
       ...
    ]
}
```

Other possible output formats are [MATSim population .xml files](doc/outputFormats/MATSimExample.xml) and
[SQLite](doc/outputFormats/SQLiteStructure.md).
The output format is inferred from the given output file extension.

Technically, OMOD will run for any location on Earth.
However, we calibrated the model using data from the German national household travel survey
(https://www.mobilitaet-in-deutschland.de/publikationen2017.html).
Therefore, the model's performance outside of Germany,
especially in nations with conditions very different from Germany's,
is uncertain.
Additionally, the region must be mapped reasonably well in OpenStreetMap.
Good mapping information about the location and size of buildings, land use zones, and the road network is especially important.
Census information of the region is not required but helpful;
see python_tools/format_zensus2011.py for an example of how to correctly format census data for OMOD.

The methodology behind the demand generation process is explained in the publication [OMOD: An open-source tool for creating disaggregated mobility demand based on OpenStreetMap](https://doi.org/10.1016/j.compenvurbsys.2023.102029).

## Get Started

You need Java 17 or a later version.

1. Download the latest release of OMOD (see *Releases* on the right)
2. Download OSM data of the region you are interested in as an osm.pbf.The file can cover a larger area than the area of interest, but too large files slow down initialization. Recommended download site: https://download.geofabrik.de/
3. Get a GeoJson of the region you want to simulate. This region must be covered by the osm.pbf file. With https://geojson.io, you can easily create a geojson of an arbitrary region. Geojsons for administrative areas can be obtained quickly with https://polygons.openstreetmap.fr/.
4. Run OMOD:

   ```
   java -jar omod-2.0.18-all.jar Path/to/GeoJson Path/to/osm.pbf 
   ```

There are multiple optional cli arguments, such as the number of agents, the number of days, or the population definition.
See all [cli options here](#CLI-Options) or run --help.

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
The second mode uses the Euclidean distance
and is significantly faster but less precise.

Change the routing mode with the CLI flag *\--routing_mode* to either *GRAPHHOPPER* or *BEELINE*.

## Population Definition

It is possible to separate the population into different strata/groups and assign each individual properties (--population_file=*path\to\file*).

Example:

```
[
  {
    "stratumName": "Young Generation",  // Can be chosen freely
    "stratumShare": 0.5,                // Must add up to 1.0 with the shares of the other strata
    "carOwnership": 0.30,               // Share of car ownership in the group
    "age": {                            
      "limits": [10, 20, 30],           // Defines the age distribution of the stratum. The limits define the upper bounds of each bin. Inside each bin, the distribution is uniform. For example, here, 25% of the group is aged between 0 (inclusive) and 10 (exclusive).
      "shares": [0.25, 0.5, 0.25],      // These values combined with the value of 'UNDEFINED' must add up to 1.0
      "UNDEFINED": 0.0
    },
    "homogenousGroup": {                // Shares of the hom. Groups in the stratum. Must add up to 1.0
      "WORKING":  0.0,
      "NON_WORKING": 0.0,
      "PUPIL_STUDENT": 0.0,
      "UNDEFINED": 1.0
    },
    "mobilityGroup": {                 // Shares of the mob. Groups in the stratum. Must add up to 1.0
      "CAR_USER": 0.2,
      "CAR_MIXED": 0.2,
      "NOT_CAR": 0.6,
      "UNDEFINED": 0.0
    },
    "sex": {                           // Shares of the sexes in the stratum. Must add up to 1.0
      "MALE": 0.5,
      "FEMALE": 0.5,
      "UNDEFINED": 0.0
    }
  },
  {
     "stratumName": "Old Generation",
     "stratumShare": 0.5,
     "carOwnership": 0.60,
     "age": {
        "limits": [30, 60, 80],
        "shares": [0.25, 0.5, 0.25],
        "UNDEFINED": 0.0
     },
     "homogenousGroup": {
        "WORKING":  0.0,
        "NON_WORKING": 0.0,
        "PUPIL_STUDENT": 0.0,
        "UNDEFINED": 1.0
     },
     "mobilityGroup": {
        "CAR_USER": 0.6,
        "CAR_MIXED": 0.2,
        "NOT_CAR": 0.2,
        "UNDEFINED": 0.0
     },
     "sex": {
        "MALE": 0.5,
        "FEMALE": 0.5,
        "UNDEFINED": 0.0
     }
  },
  ...
]
```

## Usage as Java library

First, add the jar to your classpath.

Basic example:

```java
import de.uniwuerzburg.omod.core.Omod;
import de.uniwuerzburg.omod.core.models.MobiAgent;
import de.uniwuerzburg.omod.core.models.Diary;
import de.uniwuerzburg.omod.core.models.Weekday;
import de.uniwuerzburg.omod.core.models.Activity;
import de.uniwuerzburg.omod.core.models.ActivityType;

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

## CLI Options

```
  --n_agents=<int>              Number of agents to simulate. If
                                populate_buffer_area = y, additional agents are
                                created to populate the buffer area.
  --share_pop=<float>           Share of the population to simulate. 0.0 = 0%,
                                1.0 = 100% If populate_buffer_area = y,
                                additional agents are created to populate the
                                buffer area.
  --n_days=<int>                Number of days to simulate
  --start_wd=(MO|TU|WE|TH|FR|SA|SU|HO|UNDEFINED)
                                First weekday to simulate. If the value is set
                                to UNDEFINED, all simulated days will be
                                UNDEFINED.
  --out=<path>                  Output file. The output format is inferred from
                                the ending: '.json' -> Json, '.xml'-> MATSim,
                                '.db'-> SQLite
  --routing_mode=(GRAPHHOPPER|BEELINE)
                                Distance calculation method for destination
                                choice. Either euclidean distance (BEELINE) or
                                routed distance by car (GRAPHHOPPER)
  --od=<path>                   [Experimental] Path to an OD-Matrix in GeoJSON
                                format. The matrix is used to further calibrate
                                the model to the area using k-factors.
  --census=<path>               Path to population data in GeoJSON format. For
                                an example of how to create such a file see
                                python_tools/format_zensus2011.py. Should cover
                                the entire area, but can cover more.
  --grid_precision=<float>      Allowed average distance between a focus area
                                building and its corresponding TAZ center. The
                                default is 150m and suitable in most cases.In
                                the buffer area the allowed distance increases
                                quadratically with distance. Unit: meters
  --buffer=<float>              Distance by which the focus area (defined by
                                GeoJSON) is buffered in order to account for
                                traffic generated by the surrounding. Unit:
                                meters
  --seed=<int>                  RNG seed.
  --cache_dir=<path>            Cache directory
  --populate_buffer_area=true|false
                                Determines if home locations of agents can be
                                in the buffer area (so outside of the focus
                                area). If set to 'y' additional agents will be
                                created so that the proportion of agents in and
                                outside the focus area is the same as in the
                                census data. The focus area will always be
                                populated by n_agents agents.
  --distance_matrix_cache_size=<int>
                                Maximum number of entries of the distance
                                matrix to precompute (only if routing_mode is
                                GRAPHHOPPER). A high value will lead to high
                                RAM usage and long initialization times but
                                overall significant speed gains. The default
                                value will use approximately 8 GB RAM at
                                maximum.
  --mode_choice=(NONE|CAR_ONLY|GTFS)
                                Type of mode choice. NONE: Returns trips with
                                undefined modes.GTFS: Uses a logit model with
                                public transit as an option
  --return_path_coords=true|false
                                Whether lat/lon coordinates of chosen trip
                                paths are returned.Paths only exist for trips
                                with defined modes and within the focus area +
                                buffer.
  --population_file=<path>      Path to file that describes the
                                socio-demographic makeup of the population.
                                Must be formatted like
                                omod/src/main/resources/Population.json.
  --activity_group_file=<path>  Path to file that describes the activity chains
                                for each population group and the dwell-time
                                distribution for the each chain. Must be
                                formatted like
                                omod/src/main/resources/ActivityGroup.json
  --n_worker=<int>              Number of parallel coroutines that can be
                                executed at the same time. Default: Number of
                                CPU-Cores available.
  --gtfs_file=<path>            Path to an General Transit Feed Specification
                                (GTFS) for the area. Required for public
                                transit routing,for example if public transit
                                is an option in mode choice. Must be a .zip
                                file or a directory (see https://gtfs.org/
                                ).Recommended download platform for Germany:
                                https://gtfs.de/
  --matsim_output_crs=<text>    CRS of MatSIM output. Must be a code understood
                                by org.geotools.referencing.CRS.decode().
  --mode_speed_up=<value>       Value: MODE=FACTOR. Multiply the travel time of
                                each trip of the mode by the factor.Example:
                                CAR_DRIVER=0.3, will slow down car travel
                                durations by 70%.
  -h, --help                    Show this message and exit
```

## Documentation

An API reference is available at: https://L-Strobel.github.io/omod

## Acknowledgment

This model is created as part of the ESM-Regio project (https://www.bayern-innovativ.de/de/seite/esm-regio-en)
and is made possible through funding from the German Federal Ministry for Economic Affairs and Climate Action.