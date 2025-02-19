
# Tables
## activity
| cid | name           | type    | notnull | dflt_value | pk |
|-----|----------------|---------|---------|------------|----|
| 0   | id             | INTEGER | 0       |            | 1  |
| 1   | person         | INTEGER | 1       |            | 0  |
| 2   | day            | INTEGER | 1       |            | 0  |
| 3   | legID          | INTEGER | 1       |            | 0  |
| 4   | activityType   | TEXT    | 0       |            | 0  |
| 5   | startTime      | TEXT    | 0       |            | 0  |
| 6   | stayTimeMinute | REAL    | 0       |            | 0  |
| 7   | lat            | REAL    | 0       |            | 0  |
| 8   | lon            | REAL    | 0       |            | 0  |
| 9   | dummyLoc       | INT     | 0       |            | 0  |
| 10  | inFocusArea    | INT     | 0       |            | 0  |

## day

| cid | name    | type    | notnull | dflt_value | pk |
|-----|---------|---------|---------|------------|----|
| 0   | id      | INTEGER | 0       |            | 1  |
| 1   | dayType | TEXT    | 1       |            | 0  |

## person

| cid | name            | type    | notnull | dflt_value | pk |
|-----|-----------------|---------|---------|------------|----|
| 0   | id              | INTEGER | 0       |            | 1  |
| 1   | homogenousGroup | TEXT    | 1       |            | 0  |
| 2   | mobilityGroup   | TEXT    | 1       |            | 0  |
| 3   | age             | INTEGER | 0       |            | 0  |
| 4   | sex             | TEXT    | 1       |            | 0  |
| 5   | carAccess       | INT     | 1       |            | 0  |

## trip
| cid | name              | type    | notnull | dflt_value | pk |
|-----|-------------------|---------|---------|------------|----|
| 0   | id                | INTEGER | 0       |            | 1  |
| 1   | person            | INTEGER | 1       |            | 0  |
| 2   | day               | INTEGER | 1       |            | 0  |
| 3   | legID             | INTEGER | 1       |            | 0  |
| 4   | startTime         | TEXT    | 0       |            | 0  |
| 5   | mode              | TEXT    | 0       |            | 0  |
| 6   | distanceKilometer | REAL    | 0       |            | 0  |
| 7   | timeMinute        | REAL    | 0       |            | 0  |
| 8   | route             | BLOB    | 0       |            | 0  |

## runParameters

| cid | name  | type    | notnull | dflt_value | pk |
|-----|-------|---------|---------|------------|----|
| 0   | id    | INTEGER | 0       |            | 1  |
| 1   | name  | TEXT    | 1       |            | 0  |
| 1   | value | TEXT    | 0       |            | 0  |