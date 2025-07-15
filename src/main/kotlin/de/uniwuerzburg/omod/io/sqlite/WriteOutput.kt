package de.uniwuerzburg.omod.io.sqlite

import de.uniwuerzburg.omod.io.json.OutputActivity
import de.uniwuerzburg.omod.io.json.OutputEntry
import de.uniwuerzburg.omod.io.json.OutputTrip
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTWriter
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types.INTEGER


fun writeSQLite(output: List<OutputEntry>, file: File, runParams: Map<String, String>) : Boolean {
    val url = "jdbc:sqlite:${file.absolutePath}"
    val geometryFactory = GeometryFactory()
    val wktWriter = WKTWriter()

    try {
        val conn = DriverManager.getConnection(url)
        conn.autoCommit = false

        // Person table
        conn.createStatement().execute("DROP TABLE IF EXISTS person;")
        val personTableSQL = (
            "CREATE TABLE person (" +
            "	id INTEGER PRIMARY KEY," +
            "	homogenousGroup text NOT NULL," +
            "	mobilityGroup text NOT NULL," +
            "	age INTEGER," +
            "	sex text NOT NULL," +
            "	carAccess INT NOT NULL" +
            ");"
        )
        conn.createStatement().execute(personTableSQL)

        // Day table
        conn.createStatement().execute("DROP TABLE IF EXISTS day;")
        val dayTableSQL = (
            "CREATE TABLE day (" +
            "	id INTEGER PRIMARY KEY," +
            "	dayType text NOT NULL" +
            ");"
        )
        conn.createStatement().execute(dayTableSQL)

        // Activity table
        conn.createStatement().execute("DROP TABLE IF EXISTS activity;")
        val actTableSQL = (
            "CREATE TABLE activity (" +
            "	id INTEGER PRIMARY KEY," +
            "	person INTEGER NOT NULL," +
            "	day INTEGER NOT NULL," +
            "	legID INTEGER NOT NULL," +
            "	activityType text," +
            "	startTime text," +
            "	stayTimeMinute REAL," +
            "	lat REAL," +
            "	lon REAL," +
            "	dummyLoc Int," +
            "	inFocusArea Int," +
            "	FOREIGN KEY(person) REFERENCES person(id)," +
            "	FOREIGN KEY(day) REFERENCES day(id)" +
            ");"
        )
        conn.createStatement().execute(actTableSQL)

        // Trip table
        conn.createStatement().execute("DROP TABLE IF EXISTS trip;")
        val tripTableSQL = (
        "CREATE TABLE trip (" +
                "	id INTEGER PRIMARY KEY," +
                "	person INTEGER NOT NULL," +
                "	day INTEGER NOT NULL," +
                "	legID INTEGER NOT NULL," +
                "	startTime text," +
                "	mode text," +
                "	distanceKilometer REAL," +
                "	timeMinute REAL," +
                "   route BLOB," +
                "	FOREIGN KEY(person) REFERENCES person(id)," +
                "	FOREIGN KEY(day) REFERENCES day(id)" +
                ");"
        )
        conn.createStatement().execute(tripTableSQL)

        // Run parameters table
        conn.createStatement().execute("DROP TABLE IF EXISTS runParameters;")
        val paramsTableSQL = (
        "CREATE TABLE runParameters (" +
                "	id INTEGER PRIMARY KEY," +
                "	name text NOT NULL," +
                "	value text" +
                ");"
        )
        conn.createStatement().execute(paramsTableSQL)

        // Prepare statements
        val personPStmt = conn.prepareStatement(
        "INSERT INTO person" +
            "(id, homogenousGroup, mobilityGroup, age, sex, carAccess)" +
            " VALUES(?,?,?,?,?,?)"
        )
        val dayPStmt = conn.prepareStatement(
        "INSERT INTO day" +
            "(id,dayType)" +
            " VALUES(?,?)"
        )
        val knownDayIds = mutableSetOf<Int>()
        val actPStmt = conn.prepareStatement(
        "INSERT INTO activity" +
             "(person, day, legID, activityType, startTime, stayTimeMinute, lat, lon," +
              "dummyLoc, inFocusArea)" +
             " VALUES(?,?,?,?,?,?,?,?,?,?)"
        )
        val tripPStmt = conn.prepareStatement(
        "INSERT INTO trip" +
            "(person, day, legID, startTime, mode, distanceKilometer, timeMinute, route)" +
            " VALUES(?,?,?,?,?,?,?,?)"
        )
        val runParamPStmt = conn.prepareStatement(
        "INSERT INTO runParameters" +
                "(name, value)" +
                " VALUES(?,?)"
        )

        // Run Parameters
        for ((name, value) in runParams) {
            runParamPStmt.setString(1, name)
            runParamPStmt.setString(2, value)
            runParamPStmt.executeUpdate()
        }
        conn.commit()

        // Mobility demand
        for ((i, entry) in output.withIndex()) {
            // Fill person table
            personPStmt.setInt(1, entry.id)
            personPStmt.setString(2, entry.homogenousGroup.toString())
            personPStmt.setString(3, entry.mobilityGroup.toString())
            if (entry.age == null) {
                personPStmt.setNull(4, INTEGER)
            } else {
                personPStmt.setInt(4, entry.age)
            }
            personPStmt.setString(5, entry.sex.toString())
            personPStmt.setBoolean(6, entry.carAccess)
            personPStmt.executeUpdate()

            for (day in entry.mobilityDemand) {
                // Fill day table
                if (!knownDayIds.contains(day.day)) {
                    knownDayIds.add(day.day)

                    dayPStmt.setInt(1, day.day)
                    dayPStmt.setString(2, day.dayType.toString())
                    dayPStmt.executeUpdate()
                }

                for (leg in day.plan) {
                    if (leg is OutputActivity) {
                        // Fill activity table
                        actPStmt.setInt(1, entry.id)
                        actPStmt.setInt(2, day.day)
                        actPStmt.setInt(3, leg.legID)
                        actPStmt.setString(4, leg.activityType.toString())
                        actPStmt.setString(5, leg.startTime)
                        if (leg.stayTimeMinute != null) {
                            actPStmt.setDouble(6, leg.stayTimeMinute)
                        } else {
                            actPStmt.setNull(6,  java.sql.Types.DOUBLE)
                        }
                        actPStmt.setDouble(7, leg.lat)
                        actPStmt.setDouble(8, leg.lon)
                        actPStmt.setBoolean(9, leg.dummyLoc)
                        actPStmt.setBoolean(10, leg.inFocusArea)
                        actPStmt.executeUpdate()
                    } else if (leg is OutputTrip) {
                        // Fill trip table
                        tripPStmt.setInt(1, entry.id)
                        tripPStmt.setInt(2, day.day)
                        tripPStmt.setInt(3, leg.legID)
                        tripPStmt.setString(4, leg.startTime)
                        tripPStmt.setString(5, leg.mode.toString())
                        if (leg.distanceKilometer != null) {
                            tripPStmt.setDouble(6, leg.distanceKilometer)
                        } else {
                            tripPStmt.setNull(6, java.sql.Types.DOUBLE)
                        }
                        if (leg.timeMinute != null) {
                            tripPStmt.setDouble(7, leg.timeMinute)
                        } else {
                            tripPStmt.setNull(7, java.sql.Types.DOUBLE)
                        }

                        if ((leg.lats != null) && (leg.lons != null)) {
                            val coords = leg.lats.zip(leg.lons)
                                .map { (lat, lon) -> Coordinate(lat, lon) }
                                .toTypedArray()
                            if (coords.size > 1) {
                                val line = geometryFactory.createLineString(coords)
                                val wkt = wktWriter.write(line)
                                tripPStmt.setString(8, wkt)
                            } else {
                                tripPStmt.setNull(8, java.sql.Types.VARCHAR)
                            }
                        } else {
                            tripPStmt.setNull(8, java.sql.Types.VARCHAR)
                        }
                        tripPStmt.executeUpdate()
                    }
                }
            }
            // Write batch
            if (i % 1000 == 0) {
                conn.commit()
            }
        }
        conn.commit()
        conn.close()
    } catch (e: SQLException) {
        println(e.message)
        return false
    }
    return true
}