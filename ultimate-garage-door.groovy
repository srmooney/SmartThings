/**
 *  Ultimate Garage Door
 *
 *  Copyright 2015 Sean Mooney
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Ultimate Garage Door",
    namespace: "srmooney",
    author: "Sean Mooney",
    description: "The ultimate garage door app",
    category: "My Apps",
    iconUrl: "http://cdn.device-icons.smartthings.com/Transportation/transportation13-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Transportation/transportation13-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Transportation/transportation13-icn@2x.png")


preferences {
    page(name: "page1", title: "", uninstall:true, install:true) {
        section("The Door") {
            input "doorSensor", "capability.contactSensor", title: "Which Sensor?"
            input "doorSwitch", "capability.momentary", title: "Which Relay?"
        }
        
        section("Settings") {
            href "page2", title:"Arrival / Departure"
            href "page3", title:"Night Settings"
            href "page4", title:"Notifications"
        }        
    }
    
    //Presence
    page(name: "page2", title: "", uninstall:false, install:false) {
        section("Arrival / Departure"){
            input "presenceArrive", "capability.presenceSensor", title: "Open when these people arrive:", multiple:true
            input "presenceDepart", "capability.presenceSensor", title: "Close when these people leave:", multiple:true
        }
    }
    
    //Night Time
    page(name: "page3", title: "", uninstall:false, install:false) {
        section ("Night Settings") {
            //input "closeSunset", "enum", title: "Close after sunset", required: false, metadata: [values: ["Yes","No"]]
            input "closeSunset", "bool", title: "Close after sunset", required: false
        }
        section ("Sunset offset (optional)...") {
            input "sunsetOffsetValue", "number", title: "Minutes", required: false
            input "sunsetOffsetDir", "enum", title: "Before or After", required: false, metadata: [values: ["Before","After"]]
        }
        section ("If opened after dark, close after...") {
            input "closeAfter", "number", title: "Minutes", required: false
        }
    }
    
    //Notification
    page(name: "page4", title: "", uninstall:false, install:true) {
        section () {
            input "notify", "enum", title: "Notify when...", required: false, metadata: [values: ["Opening","Closing"]], multiple: true
        }
    } 
}

def installed() {
    log.debug "Installed with settings: ${settings}"

    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"

    unsubscribe()
    initialize()
}

def initialize() {
    log.debug "Garage Door is ${doorSensor.contactState.value}"
    // TODO: subscribe to attributes, devices, locations, etc.
    subscribe(presenceArrive, "presence", presenceHandler)
    subscribe(presenceDepart, "presence", presenceHandler)
    subscribe(doorSensor, "contact", contactHandler)
    subscribe(location, "sunsetTime", sunsetTimeHandler)
    subscribe(location, "sunriseTime", sunriseTimeHandler)
    subscribe(app, appTouchHandler)

    //schedule it to run today too
    scheduleCloseGarage(location.currentValue("sunsetTime"))
    
    state.sunriseTime = location.currentValue("sunriseTime")
    state.sunsetTime = location.currentValue("sunsetTime")

    log.debug "state: $state"
}

/* Events */
def appTouchHandler(evt){
    log.debug "appTouchHandler: ${evt}"
    if (doorSensor.contactState.value == "open"){ close() }
    else { open() }
}

def sunsetTimeHandler(evt) {
    log.debug "sunset event $evt"
    sendPush("Sunset: ${evt.value}")
    scheduleCloseGarage(evt.value)
}

def sunriseTimeHandler(evt){
    state.sunriseTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", evt.value)
}

def presenceArriveHandler(evt) {
    log.trace "Arrive: ${evt}"
    if (evt.value == "present" && doorSensor.contactState.value == "closed") {
        log.debug "${evt.displayName} has arrived at the ${location}"
        open()
        if (notify.contains("Opening")){
            sendPush("Opening Garage due to ${evt.displayName}s arrival")
        }
    }
}

def presenceHandler(evt) {
    //log.debug "${evt.displayName} has left the ${location}"

    if (evt.value == "not present" && doorSensor.contactState.value == "open") {
        for (person in presenceDepart) {
            if (person.toString() == evt.displayName){
                close()
                if (notify.contains("Closing")){
                    def msg = "Closing Garage due to ${evt.displayName}s departure"
                    log.debug "$msg"
                    sendPush("$msg")
                }
                break
            }
        }
    }
    
    if (evt.value == "present" && doorSensor.contactState.value == "closed") {
        for (person in presenceArrive) {
            if (person.toString() == evt.displayName){
                open()
                if (notify.contains("Opening")){
                    def msg = "Opening Garage due to ${evt.displayName}s arrival"
                    log.debug "$msg"
                    sendPush("$msg")
                }
                break
            }
        }
    }
}

def contactHandler(evt) {
    log.debug "Contact is in ${evt.value} state"
    if(evt.value == "open" && notify.contains("Opening")) {
        sendPush("Garage opened")
    }
    if(evt.value == "closed" && notify.contains("Closing")){
        sendPush("Garage closed")
    }
    
    if (closeSunset == true && evt.value == "open" && closeAfter){
        runonce(now + closeAfter, closeWhenDark)
    }
}

/* Actions */
def close(){
    log.debug "Close Garage Door"
    doorSwitch.push()
}

def open(){
    log.debug "Open Garage Door"
    doorSwitch.push()
}

def closeWhenDark(){
    if (doorSensor.contactState.value == "open") {
        sendPush("Closing garage, open for $closeAfter mins after sunset")
        close()
    }
}

def closeAfterSunset(){
    state.sunsetTime = now
    if (closeSunset == true && doorSensor.contactState.value == "open") {
        sendPush("Closing garage after sunset")
        close()
    }
}

def scheduleCloseGarage(sunsetString) {
    def dateFormat = "yyyy-MM-dd hh:mm:ss z"
    
    log.info "sunsetString: ${sunsetString.format(dateFormat, location.timeZone)}"
    //get the Date value for the string
    def sunsetTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", sunsetString)
    
    //calculate the offset
    def offsetMinutes = (sunsetOffsetValue ? (sunsetOffsetDir == "Before" ? (sunsetOffsetValue * -1) :  sunsetOffsetValue) : 0)
    
    log.debug "Offset Minutes: $offsetMinutes"
    def sunsetOffset = new Date(sunsetTime.time + (offsetMinutes * 60 * 1000))
          

    //schedule this to run one time
    log.info "Scheduling for: ${sunsetOffset.format(dateFormat, location.timeZone)} (sunset is ${sunsetTime.format(dateFormat, location.timeZone)})"
    runOnce(sunsetOffset, closeAfterSunset)
    
    //Subtract 24hrs and if it's still later than now set as new time
    use(groovy.time.TimeCategory) {
        def test = sunsetOffset - 24.hours
        log.info("24Hrs Before: ${test.format(dateFormat, location.timeZone)}")
        def isBefore = !test.before(new Date())
        log.info("isBefore: $isBefore")
        if (isBefore == true) {
            log.info "Scheduling for: ${test.format(dateFormat, location.timeZone)} (sunset is ${sunsetTime.format(dateFormat, location.timeZone)})"
            runOnce(test, closeAfterSunset)
        }
    }
}
