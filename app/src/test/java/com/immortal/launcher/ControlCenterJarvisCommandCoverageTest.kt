/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcHomeCommand]: the voice resolver's scope, safety and word rules beyond the happy paths. */
class ControlCenterJarvisCommandCoverageTest {
  private fun e(id: String, state: String, name: String, vararg attrs: Pair<String, Any?>) =
      CcEntity(id, state, mapOf("friendly_name" to name, *attrs))

  private val k1 = e("light.kitchen_ceiling", "on", "Kitchen ceiling", "brightness" to 200)
  private val k2 = e("light.kitchen_pendant", "on", "Pendant")
  private val k3 = e("light.kitchen_strip", "off", "Under-cabinet")
  private val lamp = e("light.floor_lamp", "on", "Floor lamp")
  private val mia = e("light.mia", "on", "Mia nightlight")
  private val front = e("lock.front_door", "locked", "Front door")
  private val garage = e("cover.garage_door", "open", "Garage door", "device_class" to "garage")
  private val blinds = e("cover.living_blinds", "open", "Living blinds")
  private val valve = e("valve.main_water", "open", "Main water")
  private val thermo = e("climate.downstairs", "heat", "Downstairs", "min_temp" to 45, "max_temp" to 90, "temperature" to 68)
  private val movie = e("scene.movie", "scening", "Movie time", "entity_id" to listOf("light.floor_lamp"))
  private val panel = e("alarm_control_panel.home", "disarmed", "Alarm", "supported_features" to 7)
  private val carLock = e("lock.model_y", "locked", "Model Y")

  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 10, 8), devices = listOf(
      CcPlaced(k1.id, 0.3f, 0.5f), CcPlaced(k2.id, 0.7f, 0.5f), CcPlaced(k3.id, 0.5f, 0.2f)))
  private val livingRoom = CcRoom("living_room", "Living room", CcRect(10, 0, 12, 8), devices = listOf(
      CcPlaced(lamp.id, 0.5f, 0.5f), CcPlaced(blinds.id, 0.5f, 0.1f), CcPlaced(thermo.id, 0.2f, 0.8f)))
  private val hall = CcRoom("hall", "Hall", CcRect(0, 8, 22, 4), devices = listOf(CcPlaced(front.id, 0.5f, 0.9f)))
  private val garageRoom = CcRoom("garage", "Garage", CcRect(22, 0, 8, 12), devices = listOf(CcPlaced(garage.id, 0.5f, 0.5f)))
  private val miaRoom = CcRoom("mia", "Mia's room", CcRect(0, 0, 10, 10), devices = listOf(CcPlaced(mia.id, 0.5f, 0.5f)))
  private val plan = CcPlan(listOf(CcFloor("ground", "Ground floor", listOf(kitchen, livingRoom, hall, garageRoom)),
      CcFloor("up", "Upstairs", listOf(miaRoom))))
  private val all = listOf(k1, k2, k3, lamp, mia, front, garage, blinds, valve, thermo, movie, panel, carLock)
  private val registry = CcRegistry(areas = listOf(CcHaArea("utility", "Utility", null)), entityArea = mapOf(valve.id to "utility"),
      tesla = CcTeslaIds(name = "Y", car = mapOf(CcCarRole.LOCK to carLock.id)))

  private fun state(vararg over: CcEntity, p: CcPlan? = plan, reg: CcRegistry? = registry) =
      CcUiState(entities = (all + over).associateBy { it.id }, registry = reg, plan = p)

  private fun resolve(text: String, s: CcUiState = state()) = CcHomeCommand.resolve(s, CcHomeRequest(text))
  private fun act(text: String, s: CcUiState = state()): CcHomeIntent.Act = when (val r = resolve(text, s)) {
    is CcHomeIntent.Act -> r
    is CcHomeIntent.Unclear -> throw AssertionError("\"$text\" was unclear: ${r.reply}")
  }
  private fun unclear(text: String, s: CcUiState = state()): String = when (val r = resolve(text, s)) {
    is CcHomeIntent.Unclear -> r.reply
    is CcHomeIntent.Act -> throw AssertionError("\"$text\" acted on ${r.items.map { it.entity.id }}")
  }

  // --- scope ------------------------------------------------------------------------------

  @Test fun aNamedFloorScopesToItsRoomsAndReadsAsTheFloor() {
    val a = act("turn off the lights on the ground floor")
    assertEquals(setOf(k1.id, k2.id, lamp.id), a.items.map { it.entity.id }.toSet())
    assertEquals(1, a.already)
    assertEquals("ground floor", a.place)
    assertEquals("ground", a.floorId)
    assertEquals(listOf("kitchen", "living_room", "hall", "garage"), a.roomIds)
  }

  @Test fun downstairsFindsTheFloorByItsNameHint() {
    val a = act("turn off the downstairs lights")
    assertEquals("downstairs", a.place)
    assertEquals("ground", a.floorId)
    assertEquals(setOf(k1.id, k2.id, lamp.id), a.items.map { it.entity.id }.toSet())
  }

  @Test fun upstairsWithNoUpperFloorAsksWhichRoom() {
    val groundOnly = CcPlan(listOf(CcFloor("ground", "Ground floor", listOf(kitchen))))
    assertTrue(unclear("turn off the lights upstairs", state(p = groundOnly)).startsWith("Which room?"))
  }

  @Test fun severalRoomsReadAsAListAndKeepEveryNamedRoom() {
    val two = act("turn off the lights in the kitchen and hall")
    assertEquals("the kitchen and hall", two.place)
    assertEquals(listOf("kitchen", "hall"), two.roomIds)
    assertEquals(setOf(k1.id, k2.id), two.items.map { it.entity.id }.toSet())
    val three = act("turn off the lights in the hall, living room and kitchen")
    assertEquals("the kitchen, living room and hall", three.place)
  }

  @Test fun aRegistryAreaIsARoomToo() {
    val a = act("shut off the water in the utility")
    assertEquals(listOf(CcCalls.valveClose(valve)), a.items.map { it.call })
    assertEquals(listOf("utility"), a.roomIds)
    assertEquals("utility", a.items.single().roomId)
    assertNull(a.floorId)
  }

  @Test fun knownRoomsComeFromThePlanThenTheRegistryElseNone() {
    val withPatio = CcPlan(listOf(CcFloor("g", "Ground", listOf(kitchen, CcRoom("patio", "Patio", CcRect(0, 0, 2, 2), outdoor = true)))))
    assertEquals("Which room? I know Kitchen.", unclear("turn off the lights", state(p = withPatio)))
    assertEquals("Which room? I know Utility.", unclear("turn off the lights", state(p = null)))
    assertEquals("Which room? I know no rooms yet.", unclear("turn off the lights", state(p = null, reg = null)))
  }

  @Test fun theWholeHouseReachesEveryLockButNeverTheCar() {
    val a = act("unlock all the doors")
    assertEquals(listOf(CcCalls.unlock(front)), a.items.map { it.call })
    assertEquals("the house", a.place)
    assertEquals(listOf("hall"), a.roomIds)
    assertEquals("ground", a.floorId)
  }

  @Test fun armingAloneNeedsNoRoomAndDefaultsToHome() {
    val a = act("arm")
    assertEquals(CcHomeWhat.ALARM, a.what)
    assertEquals(listOf(CcCalls.alarm(panel, CcAlarmMode.HOME, null)), a.items.map { it.call })
    assertEquals("the house", a.place)
  }

  @Test fun aKindMissingFromTheRoomIsSaidPlainly() {
    assertEquals("I don't see any locks in the kitchen.", unclear("lock the kitchen"))
  }

  @Test fun runWithoutANamedSceneAsksWhichScene() {
    assertEquals("Which scene? Say its name.", unclear("run the party"))
  }

  // --- what each verb does to each domain --------------------------------------------------

  @Test fun temperatureWithoutANumberAsksForOne() {
    assertEquals("To what temperature?", unclear("set the heating in the living room to 5"))
  }

  @Test fun aNamedDeviceThatCantDoTheVerbIsRefused() {
    assertEquals("I can't open Front door.", unclear("open the front door"))
    assertEquals("I can't turn on Downstairs.", unclear("turn on the downstairs thermostat"))
    assertEquals("I can't dim Floor lamp.", unclear("set the floor lamp to 150%"))
  }

  @Test fun unavailableDevicesAreSkippedAndNothingLeftIsUnclear() {
    val s = state(k1.copy(state = "unavailable"), k2.copy(state = "unavailable"), k3.copy(state = "unknown"))
    assertEquals("I can't turn off the lights in the kitchen.", unclear("turn off the kitchen lights", s))
  }

  @Test fun moreThanTheLimitIsRefusedRatherThanHalfDone() {
    val lights = (1..CcHomeCommand.MAX_ITEMS + 1).map { e("light.big_$it", "on", "Bulb $it") }
    val big = CcRoom("big", "Ballroom", CcRect(0, 0, 10, 10), devices = lights.map { CcPlaced(it.id, 0.5f, 0.5f) })
    val s = CcUiState(entities = lights.associateBy { it.id }, plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(big)))))
    assertEquals("That's 41 devices at once. Try one room or floor.", unclear("turn off the ballroom lights", s))
    val atLimit = CcUiState(entities = lights.drop(1).associateBy { it.id },
        plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(big)))))
    assertEquals(CcHomeCommand.MAX_ITEMS, act("turn off the ballroom lights", atLimit).items.size)
  }

  @Test fun fansInputBooleansAndValveSwitchesUseTheirOwnCalls() {
    val fan = e("fan.bedroom", "off", "Bedroom fan")
    val guest = e("input_boolean.guest_mode", "off", "Guest mode")
    val shutoff = e("switch.water_main_shutoff", "on", "Water main shutoff")
    val s = state(fan, guest, shutoff)
    assertEquals(listOf(CcCalls.power(fan, true)), act("turn on the bedroom fan", s).items.map { it.call })
    assertEquals(listOf(CcCalls.power(guest, true)), act("turn on guest mode", s).items.map { it.call })
    val off = act("turn off the water main shutoff", s)
    assertEquals(CcHomeWhat.VALVE, off.what)
    assertEquals(listOf(CcCalls.power(shutoff, false)), off.items.map { it.call })
  }

  @Test fun turningTheWaterBackOnOpensTheValve() {
    val s = state(valve.copy(state = "closed"))
    assertEquals(listOf(CcCalls.valveOpen(valve.copy(state = "closed"))), act("turn on the main water", s).items.map { it.call })
    assertEquals(1, act("open the main water").already)
  }

  @Test fun stopAndCloseOnCoversAndAlreadyClosed() {
    assertEquals(listOf(CcCalls.coverStop(blinds)), act("stop the living room blinds").items.map { it.call })
    val closed = state(blinds.copy(state = "closed"))
    assertEquals(1, act("lower the living room blinds", closed).already)
    assertEquals(listOf(CcCalls.coverOpen(blinds.copy(state = "closed"))), act("raise the living room blinds", closed).items.map { it.call })
  }

  @Test fun brightenIsFullAndZeroOnAnOffLightIsAlreadyDone() {
    assertEquals(listOf(CcCalls.brightness(lamp, 100)), act("brighten the floor lamp").items.map { it.call })
    val a = act("set the under cabinet to 0%")
    assertEquals(1, a.already)
    assertTrue(a.items.isEmpty())
  }

  @Test fun armModesFollowTheWordsAndWhatThePanelOffers() {
    assertEquals(listOf(CcCalls.alarm(panel, CcAlarmMode.AWAY, null)), act("arm the alarm, I'm leaving").items.map { it.call })
    assertEquals(listOf(CcCalls.alarm(panel, CcAlarmMode.AWAY, null)), act("arm the alarm away").items.map { it.call })
    val homeOnly = state(panel.copy(attrs = panel.attrs + ("supported_features" to 1)))
    assertEquals("I can't arm Alarm.", unclear("arm the alarm for the night", homeOnly))
  }

  @Test fun scriptsRunByName() {
    val bedtime = e("script.bedtime", "off", "Bedtime")
    assertEquals(listOf(CcCalls.run(bedtime)), act("run bedtime", state(bedtime)).items.map { it.call })
  }

  @Test fun aSceneNameIsNotADeviceForOtherVerbs() {
    // "turn off movie time" must not reach the scene: it falls back to lights and asks for a room.
    assertTrue(unclear("turn off movie time").startsWith("Which room?"))
  }

  @Test fun noRegistryMeansNoCarFilterAndStillResolves() {
    val a = act("turn off the kitchen lights", state(reg = null))
    assertEquals(2, a.items.size)
  }

  @Test fun overlongTextIsCutBeforeMatching() {
    val padded = "turn off the kitchen lights " + "x".repeat(CcHomeCommand.MAX_TEXT)
    assertEquals(2, act(padded).items.size)
    assertTrue(unclear("x".repeat(CcHomeCommand.MAX_TEXT) + " turn off the kitchen lights").startsWith("I can"))
  }

  // --- words ------------------------------------------------------------------------------

  @Test fun verbsFromTheirWords() {
    val cases = mapOf(
        "disarm the alarm" to CcHomeVerb.DISARM, "arm it" to CcHomeVerb.ARM, "unlock it" to CcHomeVerb.UNLOCK,
        "lock up" to CcHomeVerb.LOCK, "set it to 40 percent" to CcHomeVerb.BRIGHTNESS, "dim" to CcHomeVerb.BRIGHTNESS,
        "brightness" to CcHomeVerb.BRIGHTNESS, "make it 70 degrees" to CcHomeVerb.TEMPERATURE,
        "set the heat to 2" to CcHomeVerb.TEMPERATURE, "power the tv off" to CcHomeVerb.OFF, "lights off" to CcHomeVerb.OFF,
        "switch the hall on" to CcHomeVerb.ON, "lights on" to CcHomeVerb.ON, "raise the blinds" to CcHomeVerb.OPEN,
        "shut the blinds" to CcHomeVerb.CLOSE, "lower the blinds" to CcHomeVerb.CLOSE, "stop" to CcHomeVerb.STOP,
        "activate party" to CcHomeVerb.RUN, "start the vacuum" to CcHomeVerb.RUN, "goodnight" to CcHomeVerb.RUN,
        "movie time" to CcHomeVerb.RUN)
    cases.forEach { (text, verb) -> assertEquals(text, verb, CcHomeCommand.verb(text)) }
    assertNull(CcHomeCommand.verb("make me a sandwich"))
  }

  @Test fun nounsFromTheirWordsAndSwitchAsAVerbIsNotANoun() {
    val cases = mapOf(
        "the garage door" to CcHomeWhat.GARAGE, "the water" to CcHomeWhat.VALVE, "a bulb" to CcHomeWhat.LIGHTS,
        "the fans" to CcHomeWhat.FANS, "the curtains" to CcHomeWhat.BLINDS, "the ac" to CcHomeWhat.CLIMATE,
        "the television" to CcHomeWhat.MEDIA, "security" to CcHomeWhat.ALARM, "the doors" to CcHomeWhat.LOCKS,
        "the plug" to CcHomeWhat.SWITCHES, "turn off the switch" to CcHomeWhat.SWITCHES, "scenes" to CcHomeWhat.SCENE)
    cases.forEach { (text, what) -> assertEquals(text, what, CcHomeCommand.what(text)) }
    assertNull(CcHomeCommand.what("switch the hall off"))
    assertNull(CcHomeCommand.what("switch off the hall"))
  }

  @Test fun kindsOfEntities() {
    val cases = mapOf(
        e("light.a", "on", "A") to CcHomeWhat.LIGHTS,
        e("switch.a", "on", "Coffee") to CcHomeWhat.SWITCHES,
        e("switch.b", "on", "Desk lamp") to CcHomeWhat.LIGHTS,
        e("switch.main_water", "on", "Main") to CcHomeWhat.VALVE,
        e("fan.a", "on", "A") to CcHomeWhat.FANS,
        e("lock.a", "locked", "A") to CcHomeWhat.LOCKS,
        e("cover.g", "open", "G", "device_class" to "gate") to CcHomeWhat.GARAGE,
        e("cover.b", "open", "B") to CcHomeWhat.BLINDS,
        e("valve.a", "open", "A") to CcHomeWhat.VALVE,
        e("climate.a", "heat", "A") to CcHomeWhat.CLIMATE,
        e("media_player.a", "off", "A") to CcHomeWhat.MEDIA,
        e("scene.a", "x", "A") to CcHomeWhat.SCENE,
        e("script.a", "off", "A") to CcHomeWhat.SCENE,
        e("alarm_control_panel.a", "disarmed", "A") to CcHomeWhat.ALARM)
    cases.forEach { (entity, what) -> assertEquals(entity.id, what, CcHomeCommand.kindOf(entity)) }
    assertNull(CcHomeCommand.kindOf(e("camera.a", "idle", "A")))
    assertNull(CcHomeCommand.kindOf(e("switch.washer", "on", "Washer")))
    assertNull(CcHomeCommand.kindOf(e("sensor.t", "20", "T")))
  }

  @Test fun aliasesDropGenericWordsAndShortNames() {
    assertEquals(listOf("the den", "den"), CcHomeCommand.aliases("The den"))
    assertEquals(emptyList<String>(), CcHomeCommand.aliases("Room"))
    assertEquals(emptyList<String>(), CcHomeCommand.aliases("X"))
  }

  @Test fun roomOfPrefersThePlanThenTheRegistry() {
    val s = state()
    assertEquals("kitchen", CcHomeCommand.roomOf(s, k1.id))
    assertEquals("utility", CcHomeCommand.roomOf(s, valve.id))
    assertNull(CcHomeCommand.roomOf(s, "light.nowhere"))
    assertNull(CcHomeCommand.roomOf(CcUiState(), k1.id))
  }

  @Test fun verbWordsAndNumbers() {
    val words = CcHomeVerb.entries.associateWith { CcHomeCommand.verbWord(it) }
    assertEquals(mapOf(CcHomeVerb.ON to "turn on", CcHomeVerb.OFF to "turn off", CcHomeVerb.LOCK to "lock",
        CcHomeVerb.UNLOCK to "unlock", CcHomeVerb.OPEN to "open", CcHomeVerb.CLOSE to "close", CcHomeVerb.STOP to "stop",
        CcHomeVerb.BRIGHTNESS to "dim", CcHomeVerb.TEMPERATURE to "set", CcHomeVerb.RUN to "run", CcHomeVerb.ARM to "arm",
        CcHomeVerb.DISARM to "disarm"), words)
    assertEquals("70", CcHomeCommand.fmt(70.0))
    assertEquals("21.5", CcHomeCommand.fmt(21.5))
  }
}
