package de.uniwuerzburg.omod.io.matsim

import de.uniwuerzburg.omod.io.json.OutputActivity
import de.uniwuerzburg.omod.io.json.OutputEntry
import de.uniwuerzburg.omod.io.json.OutputTrip
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

fun writeSingleDay(output: List<OutputEntry>, file: File, day: Int) : Boolean {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isValidating = true

    try {
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()
        doc.xmlStandalone = true

        val root = doc.createElement("population")
        root.setAttribute("desc", "Generated with OMOD: https://github.com/L-Strobel/omod")

        for (entry in output) {
            val person = doc.createElement("person")
            person.setAttribute("id", entry.id.toString())
            val matSimSex = entry.sex.matSimName()
            if (matSimSex != null) {
                person.setAttribute("sex", matSimSex)
            }
            if (entry.age != null) {
                person.setAttribute("age", entry.age.toString())
            }
            val carAvailability = if (entry.carAccess) { "always" } else { "never" }
            person.setAttribute("car_avail", carAvailability)
            val employed = entry.homogenousGroup.matSimName()
            if (employed != null) {
                person.setAttribute("employed", employed)
            }

            val plan = doc.createElement("plan")
            plan.setAttribute("selected", "yes")
            for (leg in entry.mobilityDemand[day].plan) {
                if (leg is OutputActivity) {
                    val activity = doc.createElement("activity")
                    activity.setAttribute("type", leg.activityType.matSimName())
                    activity.setAttribute("x", leg.lat.toString())
                    activity.setAttribute("y", leg.lon.toString())

                    val time = if (leg.stayTimeMinute == null) {
                        null
                    } else {
                        val hour =   (leg.stayTimeMinute / 60).toInt()
                        val minute = (leg.stayTimeMinute % 60).toInt()
                        val second = ((leg.stayTimeMinute % 60 - minute) * 60).toInt()
                        "%02d:%02d:%02d".format(hour, minute, second)
                    }
                    if (leg.legID == 0) {
                        activity.setAttribute("end_time", time ?: "23:59:59")
                    } else {
                        if (time != null) {
                            activity.setAttribute("max_dur", time)
                        }
                    }

                    plan.appendChild(activity)
                } else if (leg is OutputTrip) {
                    val trip = doc.createElement("leg")
                    trip.setAttribute("mode", leg.mode.matSimName())
                    plan.appendChild(trip)
                }
            }

            person.appendChild(plan)
            root.appendChild(person)
        }
        doc.appendChild(root)

        try {
            val transformer = TransformerFactory.newInstance().newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty(OutputKeys.METHOD, "xml")
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            transformer.setOutputProperty(
                OutputKeys.DOCTYPE_SYSTEM, "http://www.matsim.org/files/dtd/population_v6.dtd"
            )
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")

            // send DOM to file
            transformer.transform(DOMSource(doc), StreamResult(file))

        } catch (te: TransformerException) {
            println(te.message)
            return false
        } catch (ioe: IOException) {
            println(ioe.message)
            return false
        }
    } catch (pce: ParserConfigurationException) {
        println("UsersXML: Error trying to instantiate DocumentBuilder $pce")
        return false
    }
    return true
}

fun writeMatSim(output: List<OutputEntry>, file: File, nDays: Int) : Boolean {
    var success = true
    if (nDays == 1) {
        success = writeSingleDay(output, file, 0)
    } else {
        for (day in 0 until nDays) {
            val dayFile = File(file.parent, file.nameWithoutExtension + "_day$day.xml" )
            success = success && writeSingleDay(output, dayFile, day)
        }
    }
    return  success
}