--[[
    Knox Link — what the screens get to see.

    core   (4x a second, always): clock, weather, position, vitals bars, moodles, hands, vehicle
    body   (2x a second, while the Vitals tab is open): every body part and its wounds
    gear   (while the Gear tab is open, when something changed): carried items and what you can do with them
    skills (every 2 s while the Skills tab is open)

    Every value is read through the same calls the game's own UI uses, and only what the
    game's own panels already show the player is sent (no hidden infection, no zombie radar).
]]

require "KnoxLink/KnoxLink_Core"

local KL = KnoxLink
local round, clamp01, try = KL.round, KL.clamp01, KL.try

-- ----------------------------------------------------------------------------- core

local MOODLES = {
    "ENDURANCE", "TIRED", "HUNGRY", "PANIC", "SICK", "BORED", "UNHAPPY", "BLEEDING", "WET",
    "HAS_A_COLD", "ANGRY", "STRESS", "THIRST", "INJURED", "PAIN", "HEAVY_LOAD", "DRUNK",
    "ZOMBIE", "HYPERTHERMIA", "HYPOTHERMIA", "WINDCHILL", "CANT_SPRINT", "UNCOMFORTABLE",
    "NOXIOUS_SMELL", "FOOD_EATEN",
}

-- Stats: Build 42.13+ reads them through the CharacterStat enum; Build 41 and early 42 through
-- one getter each. Every entry: getter on Stats or BodyDamage, and its range.
local LEGACY_STATS = {
    ENDURANCE = { "s", "getEndurance", 1 }, HUNGER = { "s", "getHunger", 1 }, THIRST = { "s", "getThirst", 1 },
    FATIGUE = { "s", "getFatigue", 1 }, STRESS = { "s", "getStress", 1 }, PANIC = { "s", "getPanic", 100 },
    PAIN = { "s", "getPain", 100 }, SICKNESS = { "s", "getSickness", 1 },
    BOREDOM = { "b", "getBoredomLevel", 100 }, UNHAPPINESS = { "b", "getUnhappynessLevel", 100 },
    WETNESS = { "b", "getWetness", 100 }, TEMPERATURE = { "b", "getTemperature", 1 },
}

local function rawStat(player, key)
    local enum = CharacterStat and CharacterStat[key]
    if enum then
        local stats = player:getStats()
        local ok, v = pcall(stats.get, stats, enum)
        if ok and type(v) == "number" then return v, enum end
    end
    local l = LEGACY_STATS[key]
    if l then
        local obj = l[1] == "s" and player:getStats() or player:getBodyDamage()
        local v = try(obj, l[2], nil)
        if type(v) == "number" then return v, nil, l[3] end
    end
    return 0
end

-- 0..1 position of a stat between the game's own limits for it
local function stat(player, key)
    local v, enum, legacyMax = rawStat(player, key)
    if enum then
        local lo, hi = try(enum, "getMinimumValue", 0), try(enum, "getMaximumValue", 1)
        if hi <= lo then return clamp01(v) end
        return clamp01((v - lo) / (hi - lo))
    end
    return clamp01(v / (legacyMax or 1))
end

--- Remaining uses of a drainable (battery, pills, lighter...), 0..1, on any build.
function KL.usesLeft(item)
    local v = try(item, "getCurrentUsesFloat", nil)
    if v == nil then v = try(item, "getUsedDelta", nil) end
    return v
end

-- Moodles: Build 42.13+ names them in capitals, Build 41 and early 42 in CamelCase.
local MOODLE_CAMEL = {
    ENDURANCE = "Endurance", TIRED = "Tired", HUNGRY = "Hungry", PANIC = "Panic", SICK = "Sick", BORED = "Bored",
    UNHAPPY = "Unhappy", BLEEDING = "Bleeding", WET = "Wet", HAS_A_COLD = "HasACold", ANGRY = "Angry",
    STRESS = "Stress", THIRST = "Thirst", INJURED = "Injured", PAIN = "Pain", HEAVY_LOAD = "HeavyLoad",
    DRUNK = "Drunk", DEAD = "Dead", ZOMBIE = "Zombie", HYPERTHERMIA = "Hyperthermia", HYPOTHERMIA = "Hypothermia",
    WINDCHILL = "Windchill", CANT_SPRINT = "CantSprint", UNCOMFORTABLE = "Uncomfortable",
    NOXIOUS_SMELL = "NoxiousSmell", FOOD_EATEN = "FoodEaten",
}

local function moodleType(key)
    if not MoodleType then return nil end
    local ok, t = pcall(function() return MoodleType[key] end)
    if ok and t then return t end
    ok, t = pcall(function() return MoodleType[MOODLE_CAMEL[key]] end)
    return ok and t or nil
end

--- level, goodBadNeutral, name, description of one moodle, on any build.
local function moodleInfo(moodles, mt, key)
    local lvl = try(moodles, "getMoodleLevel", nil, mt)
    local idx = nil
    if lvl == nil or not try(moodles, "getMoodleDisplayString", nil, mt) then
        local ok, i = pcall(function() return MoodleType.ToIndex(mt) end)
        if ok then idx = i end
    end
    if lvl == nil and idx then lvl = try(moodles, "getMoodleLevel", 0, idx) end
    if not lvl or lvl <= 0 then return 0 end
    local g = try(moodles, "getGoodBadNeutral", nil, mt)
    if g == nil and idx then g = try(moodles, "getGoodBadNeutral", 0, idx) end
    local n = try(moodles, "getMoodleDisplayString", nil, mt)
    if n == nil and idx then n = try(moodles, "getMoodleDisplayString", key, idx) end
    local d = try(moodles, "getMoodleDescriptionString", nil, mt)
    if d == nil and idx then d = try(moodles, "getMoodleDescriptionString", "", idx) end
    return lvl, g or 0, n or key, d or ""
end

--- Name of the item's icon in the game's texture packs ("Item_Bat"), for the second screen to draw.
function KL.iconOf(item)
    local tex = try(item, "getTex", nil)
    local name = tex and try(tex, "getName", nil)
    if type(name) ~= "string" then return nil end
    name = name:gsub("^.*[/\\]", ""):gsub("%.png$", "")
    return name
end

local function itemBrief(player, item)
    if not item then return nil end
    local t = {
        id = item:getID(),
        n = item:getDisplayName(),
        c = try(item, "getDisplayCategory", nil) or try(item, "getCategory", ""),
        ic = KL.iconOf(item),
    }
    local cmax = try(item, "getConditionMax", 0)
    if cmax and cmax > 0 then t.cond = round(clamp01(item:getCondition() / cmax), 2) end
    if instanceof(item, "HandWeapon") and try(item, "isRanged", false) then
        t.ammo = try(item, "getCurrentAmmoCount", 0)
        t.ammoMax = try(item, "getMaxAmmo", 0)
        if try(item, "isRoundChambered", false) then t.chambered = true end
    end
    if instanceof(item, "DrainableComboItem") then t.use = round(clamp01(KL.usesLeft(item) or 0), 2) end
    return t
end

local function vehicleInfo(player)
    local v = player:getVehicle()
    if not v then return nil end
    local out = {
        spd = round(math.abs(try(v, "getCurrentSpeedKmHour", 0)), 0),
        max = round(try(v, "getMaxSpeed", 0), 0),
        fuel = round(clamp01(try(v, "getRemainingFuelPercentage", 0) / 100), 3),
        run = try(v, "isEngineRunning", false),
        lights = try(v, "getHeadlightsOn", false),
        hasLights = try(v, "hasHeadlights", false),
        gear = try(v, "getTransmissionNumberLetter", ""),
        drv = try(v, "isDriver", false, player),
        -- for the dashboard, the same values the game's own dashboard reads
        rpm = round(try(v, "getEngineSpeed", 0), 0),
        keys = try(v, "isKeysInIgnition", false),
        hotwired = try(v, "isHotwired", false),
        locked = try(v, "isAnyDoorLocked", false),
        regulator = try(v, "isRegulator", false),
    }
    local script = try(v, "getScript", nil)
    local sname = script and try(script, "getName", nil)
    if sname then
        local txt = getTextOrNull("IGUI_VehicleName" .. sname)
        out.name = txt or sname
    end
    local function part(id)
        return try(v, "getPartById", nil, id)
    end
    local engine = part("Engine")
    if engine then out.eng = round(try(engine, "getCondition", 0), 0) end
    local battery = part("Battery")
    local bItem = battery and try(battery, "getInventoryItem", nil)
    if bItem then out.batt = round(clamp01(KL.usesLeft(bItem) or 0), 2) end
    local heater = part("Heater")
    if heater then
        local md = try(heater, "getModData", nil)
        out.heater = md and md.active == true or false
        out.hasHeater = true
    end
    local tires = {}
    for _, id in ipairs({ "TireFrontLeft", "TireFrontRight", "TireRearLeft", "TireRearRight" }) do
        local p = part(id)
        if p then
            local present = try(p, "getInventoryItem", nil) ~= nil
            table.insert(tires, present and round(try(p, "getCondition", 0), 0) or -1)
        end
    end
    if #tires > 0 then out.tires = tires end

    -- The game's own mechanics overlay for this car: base image + one image per part.
    local overlay = ISCarMechanicsOverlay and script and ISCarMechanicsOverlay.CarList[try(script, "getFullName", "")]
    if overlay and overlay.imgPrefix and ISCarMechanicsOverlay.PartList then
        local mparts = {}
        local count = try(v, "getPartCount", 0)
        for i = 0, count - 1 do
            local p = v:getPartByIndex(i)
            local def = ISCarMechanicsOverlay.PartList[p:getId()]
            if def and def.img then
                local present = (try(p, "getItemType", nil) == nil) or try(p, "getInventoryItem", nil) ~= nil
                table.insert(mparts, { img = def.img, c = present and round(try(p, "getCondition", 0), 0) or -1 })
            end
        end
        out.mech = { prefix = overlay.imgPrefix, parts = mparts }
    end
    return out
end

-- ----------------------------------------------------------------------------- immersion

--- The game's own clock rule (zombie.ui.Clock): a worn watch, or an alarm clock that is worn or
--- held, shows the time; only a DIGITAL one also shows the date and the temperature.
function KL.clockOf(player)
    local function isClock(it)
        return it and (instanceof(it, "AlarmClockClothing") or instanceof(it, "AlarmClock"))
    end
    local function digital(it)
        -- Build 42: ItemTag.DIGITAL; Build 41: the tag is the string "Digital". (Only the call that
        -- fits this build is made: a Java exception counts as a game error even inside pcall.)
        if ItemTag and ItemTag.DIGITAL then
            local ok, d = pcall(function() return it:hasTag(ItemTag.DIGITAL) end)
            return ok and d == true
        end
        local ok, d = pcall(function() return it:getTags():contains("Digital") end)
        return ok and d == true
    end
    local worn = try(player, "getWornItems", nil)
    if worn then
        for i = 0, worn:size() - 1 do
            local it = worn:getItemByIndex(i)
            if isClock(it) then return true, digital(it) end
        end
    end
    local items = player:getInventory():getItems()
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        if isClock(it) and (try(it, "isWorn", false) or try(it, "isEquipped", false)) then return true, digital(it) end
    end
    return false, false
end

--- Immersion on (the default): screens only get what the character could know in the game.
function KL.immersive()
    local opt = KL.options and KL.options.immersion
    if not opt then return true end
    return opt:getValue() == true
end

local function mapRules(player)
    local allowed = true
    if ISWorldMap and ISWorldMap.IsAllowed then
        local ok, r = pcall(ISWorldMap.IsAllowed)
        if ok then allowed = r == true end
    end
    local map = SandboxVars and SandboxVars.Map or {}
    local dark = false
    if map.MapNeedsLight == true then
        -- the in-game map can't be read in the dark either
        local sq = player:getCurrentSquare()
        local ok, light = pcall(function() return sq:getLightLevel(player:getPlayerNum()) end)
        dark = ok and type(light) == "number" and light < 0.43
    end
    return { allowed = allowed, allKnown = map.MapAllKnown == true, dark = dark }
end

local nextBedAt, bedNear = 0, false
local nextCanAt, canDo = 0, nil

KL.addCollector("core", 250, function(player)
    local gt = getGameTime()
    local cm = getClimateManager()
    local body = player:getBodyDamage()
    local moodles = player:getMoodles()

    local sc = UIManager.getSpeedControls and UIManager.getSpeedControls()
    local speed = sc and try(sc, "getCurrentGameSpeed", 1) or 1

    local hour, minute = gt:getHour(), gt:getMinutes()
    local immersive = KL.immersive()
    local hasClock, digital = KL.clockOf(player)
    local data = {
        v = 1,
        game = { speed = speed, mp = isClient() == true },
        rules = {
            imm = immersive,
            clock = hasClock or not immersive,
            digital = digital or not immersive,
            map = immersive and mapRules(player) or { allowed = true, allKnown = true, dark = false },
        },
        time = {
            y = gt:getYear(), mo = gt:getMonth() + 1, d = gt:getDay() + 1,
            h = hour, mi = minute,
            ds = round(player:getHoursSurvived() / 24, 1),
        },
        wx = {
            t = round(try(cm, "getTemperature", 0), 1),
            rain = round(try(cm, "getRainIntensity", 0), 2),
            snow = round(try(cm, "getSnowIntensity", 0), 2),
            fog = round(try(cm, "getFogIntensity", 0), 2),
            cloud = round(try(cm, "getCloudIntensity", 0), 2),
            wind = round(try(cm, "getWindspeedKph", 0), 0),
            storm = try(cm, "getIsThunderStorming", false),
            light = round(try(cm, "getDayLightStrength", 1), 2),
        },
        p = {
            name = player:getDescriptor():getForename() .. " " .. player:getDescriptor():getSurname(),
            x = round(player:getX(), 1), y = round(player:getY(), 1), z = math.floor(player:getZ()),
            dir = round(try(player, "getDirectionAngle", 0), 0),
            out = try(player, "isOutside", false),
            sneak = try(player, "isSneaking", false),
            run = try(player, "isRunning", false) or try(player, "isSprinting", false),
            aim = try(player, "isAiming", false),
            sleep = try(player, "isAsleep", false),
            kills = try(player, "getZombieKills", 0),
            female = try(player, "isFemale", false),
        },
        bars = {
            hp = round(try(body, "getOverallBodyHealth", 100) / 100, 2),
            endu = round(stat(player, "ENDURANCE"), 2),
            hunger = round(stat(player, "HUNGER"), 2),
            thirst = round(stat(player, "THIRST"), 2),
            fatigue = round(stat(player, "FATIGUE"), 2),
            stress = round(stat(player, "STRESS"), 2),
            panic = round(stat(player, "PANIC"), 2),
            boredom = round(stat(player, "BOREDOM"), 2),
            unhappy = round(stat(player, "UNHAPPINESS"), 2),
            pain = round(stat(player, "PAIN"), 2),
            sick = round(stat(player, "SICKNESS"), 2),
            wet = round(stat(player, "WETNESS"), 2),
            temp = round((rawStat(player, "TEMPERATURE")), 1),
            weight = round(try(player, "getInventoryWeight", 0), 1),
            weightMax = round(try(player, "getMaxWeight", 0), 1),
        },
        hands = {
            p = itemBrief(player, player:getPrimaryHandItem()),
        },
        veh = vehicleInfo(player),
        wp = KL.waypoint,
        base = KL.basePos and KL.basePos(player) or nil,
        -- how many radio lines and chat messages so far, so screens can show "new"
        cnt = { radio = KL.radioCount or 0, chat = KL.chatCount or 0 },
    }
    -- the game's frame-rate cap, and the device's temperatures where it has them (handhelds)
    local perf = getPerformance and getPerformance()
    if perf then
        data.perf = { cap = try(perf, "getFramerate", nil), unc = try(perf, "isFramerateUncapped", false) or nil }
        -- Project Viewpoint's own cap, when it has taken the option over (0 = uncapped, -1 = follows the game's)
        local caps = KL.viewpointCaps and KL.viewpointCaps()
        if caps then
            local ok, c = pcall(caps.gameCap)
            if ok and c and c >= 0 then data.perf.cap, data.perf.unc = c, (c == 0) or nil end
        end
        if KnoxLink_device then
            local ok, d = pcall(KnoxLink_device)
            if ok and d then data.perf.cpu, data.perf.gpu, data.perf.bat = d.cpu, d.gpu, d.bat end
        end
        -- the game's "show the survival guide" option (it opens when a save starts)
        data.perf.guide = try(getCore(), "getOptionShowSurvivalGuide", nil)
        -- the performance mode and the heat control (KnoxLink_Perf)
        if KL.perfState then
            local ps = KL.perfState()
            data.perf.mode, data.perf.auto, data.perf.held, data.perf.bodies = ps.mode, ps.auto, ps.held, ps.bodies
            data.perf.rest = ps.rest
        end
    end

    -- Project Viewpoint's camera (first, third, isometric), when that mod runs
    data.view = KL.viewState and KL.viewState() or nil

    -- the last forecast heard on the radio, and a day count to tell how old it is
    data.fc = player:getModData().KnoxLinkForecast
    data.di = KL.dayIndex and KL.dayIndex() or nil
    -- a bed close by (offers "Sleep" instead of "Rest"); looked for once a second or so
    local nowMs = getTimestampMs()
    if nowMs >= nextBedAt then
        nextBedAt = nowMs + 1500
        bedNear = KL.nearestBed ~= nil and KL.nearestBed(player, 6) ~= nil
    end
    if bedNear then data.p.bed = true end
    -- what the item actions (bandage, eat, drink, painkiller) have to work with; looked at now and then
    if nowMs >= nextCanAt then
        nextCanAt = nowMs + 1500
        local ok, c = pcall(function() return KL.quickAvailable and KL.quickAvailable(player) end)
        canDo = ok and c or nil
    end
    data.can = canDo

    local secondary = player:getSecondaryHandItem()
    if secondary and secondary ~= player:getPrimaryHandItem() then
        data.hands.s = itemBrief(player, secondary)
    elseif secondary then
        data.hands.both = true
    end

    if immersive then
        -- Only what the game itself would show this character:
        -- no watch -> no time; an analog watch -> time only; numbers for needs -> moodles only.
        if not hasClock then
            data.time = nil
        elseif not digital then
            data.time = { h = hour, mi = minute }
        else
            data.time.ds = nil
        end
        if not digital then data.wx.t = nil end
        data.wx.wind = data.wx.wind >= 30 and 30 or nil   -- "windy", not a reading
        data.bars = {
            weight = data.bars.weight,
            weightMax = data.bars.weightMax,
        }
        if data.rules.map.allowed ~= true or data.rules.map.dark then
            data.p.out = nil
        end
    end

    local list = {}
    for _, key in ipairs(MOODLES) do
        local mt = moodleType(key)
        if mt then
            local lvl, g, n, d = moodleInfo(moodles, mt, key)
            if lvl and lvl > 0 then
                table.insert(list, { id = key, l = lvl, g = g, n = n, d = d })
            end
        end
    end
    -- Worst first: bad moodles by level, then neutral, then good.
    table.sort(list, function(a, b)
        local ra = (a.g == 2 and 0 or (a.g == 0 and 1 or 2))
        local rb = (b.g == 2 and 0 or (b.g == 0 and 1 or 2))
        if ra ~= rb then return ra < rb end
        return a.l > b.l
    end)
    data.m = list
    return data
end)

-- ----------------------------------------------------------------------------- body

local function isBandage(item)
    return try(item, "isCanBandage", false) == true
end

--- Every item the player carries, bags included (depth-limited).
function KL.allItems(player)
    local out = {}
    local function walk(container, depth)
        local items = container:getItems()
        for i = 0, items:size() - 1 do
            local it = items:get(i)
            table.insert(out, it)
            if depth < 3 and instanceof(it, "InventoryContainer") then walk(it:getItemContainer(), depth + 1) end
        end
    end
    walk(player:getInventory(), 0)
    return out
end

function KL.bestBandage(player)
    local best, bestScore = nil, -1
    for _, it in ipairs(KL.allItems(player)) do
        if isBandage(it) then
            -- Sterilized and real bandages before rags; the game's own sort uses bandage power.
            local score = try(it, "getBandagePower", 1)
            if string.find(it:getFullType(), "Sterilized", 1, true) or string.find(it:getFullType(), "Alcohol", 1, true) then score = score + 10 end
            if score > bestScore then best, bestScore = it, score end
        end
    end
    return best
end

local treatFor, treatAt, treatList = nil, 0, {}

KL.addCollector("body", 500, function(player)
    local bd = player:getBodyDamage()
    local parts = bd:getBodyParts()
    local list = {}
    for i = 0, parts:size() - 1 do
        local bp = parts:get(i)
        local ptype = bp:getType()
        local name = tostring(ptype)
        local ok, disp = pcall(BodyPartType.getDisplayName, ptype)
        local entry = {
            i = i,
            k = name,
            n = (ok and disp) or name,
            hp = round(try(bp, "getHealth", 100), 0),
        }
        if try(bp, "bleeding", false) then entry.bleed = true end
        if try(bp, "scratched", false) then entry.scratch = true end
        if try(bp, "isCut", false) then entry.cut = true end
        if try(bp, "deepWounded", false) then entry.deep = true end
        if try(bp, "bitten", false) then entry.bite = true end
        if try(bp, "getBurnTime", 0) > 0 then entry.burn = true end
        if try(bp, "getFractureTime", 0) > 0 then entry.fracture = true end
        if try(bp, "getSplintFactor", 0) > 0 then entry.splint = true end
        if try(bp, "bandaged", false) then
            entry.band = true
            if try(bp, "isBandageDirty", false) then entry.dirty = true end
        end
        if try(bp, "stitched", false) then entry.stitch = true end
        if try(bp, "haveGlass", false) then entry.glass = true end
        if try(bp, "haveBullet", false) then entry.bullet = true end
        if try(bp, "isInfectedWound", false) then entry.infected = true end
        local pain = try(bp, "getPain", nil)
        if pain and pain > 0 then entry.pain = round(pain, 0) end
        -- what the game's health panel says about it, in its words
        if KL.woundLines then
            local okL, lines = pcall(KL.woundLines, player, bp)
            if okL and #lines > 0 then entry.lines = lines end
        end
        table.insert(list, entry)
    end
    local bandage = KL.bestBandage(player)
    local out = {
        parts = list,
        hp = round(try(bd, "getOverallBodyHealth", 100), 0),
        bandage = bandage and bandage:getDisplayName() or nil,
    }
    -- the body part a screen has open: the treatments the game's health panel offers there now
    local sel = KL.healthPart
    if sel and KL.treatments and sel >= 0 and sel < parts:size() then
        local now = getTimestampMs()
        if KL.treatDirty or sel ~= treatFor or now - treatAt > 1000 then
            treatFor, treatAt, KL.treatDirty = sel, now, false
            local okT, ts = pcall(KL.treatments, player, parts:get(sel))
            treatList = {}
            if okT then
                for _, t in ipairs(ts) do table.insert(treatList, { a = t.a, i = t.i, ic = t.ic }) end
            end
        end
        out.sel = { i = sel, t = treatList }
    end
    return out
end)

-- ----------------------------------------------------------------------------- gear

local function category(item)
    local c = try(item, "getDisplayCategory", nil)
    if c == nil or c == "" then c = try(item, "getCategory", "Item") end
    return c
end

local function inHands(player, item)
    return player:getPrimaryHandItem() == item or player:getSecondaryHandItem() == item
end

local function actionsFor(player, item)
    local a = {}
    local equipped = player:isEquipped(item)
    -- worn = equipped but not held: clothing, a backpack on the back, a belt, ...
    local worn = equipped and not inHands(player, item)
    if instanceof(item, "HandWeapon") or category(item) == "Weapon" or category(item) == "Tool"
            or category(item) == "LightSource" then
        if player:getPrimaryHandItem() ~= item then table.insert(a, "equip1") end
        if player:getSecondaryHandItem() ~= item and not try(item, "isRequiresEquippedBothHands", false) then
            table.insert(a, "equip2")
        end
    end
    if inHands(player, item) then table.insert(a, "unequip") end
    if instanceof(item, "Food") and not try(item, "isFrozen", false) then table.insert(a, "eat") end
    local fc = try(item, "getFluidContainer", nil)
    if fc and not try(fc, "isEmpty", true) then
        table.insert(a, "drink")
    elseif not fc and try(item, "isWaterSource", false) and (KL.usesLeft(item) or 0) > 0 then
        table.insert(a, "drink")   -- Build 41 water bottles
    end
    if instanceof(item, "Literature") then table.insert(a, "read") end
    if string.find(item:getFullType(), "Pills", 1, true) then table.insert(a, "pill") end
    if instanceof(item, "Clothing") and not worn then table.insert(a, "wear") end
    if worn then table.insert(a, "takeoff") end
    return a
end

--- Where a worn item sits, in the game's words where it has them ("Shirt", "Hat", "Back"...).
local function wornWhere(item)
    local loc = try(item, "getBodyLocation", nil)
    if loc == nil or tostring(loc) == "" then loc = try(item, "canBeEquipped", nil) end
    if loc == nil then return nil end
    local s = tostring(loc):gsub("^%w+:", "")   -- "base:shirt" -> "shirt"
    if s == "" or s == "null" then return nil end
    local key = s:sub(1, 1):upper() .. s:sub(2)
    return getTextOrNull("UI_ClothingType_" .. key) or (key:gsub("(%l)(%u)", "%1 %2"):gsub("_", " "))
end

local function itemEntry(player, item)
    local e = {
        id = item:getID(),
        n = item:getDisplayName(),
        t = item:getFullType(),
        c = category(item),
        w = round(try(item, "getActualWeight", 0), 2),
        a = actionsFor(player, item),
        ic = KL.iconOf(item),
    }
    if inHands(player, item) then
        e.eq = true
    elseif player:isEquipped(item) then
        e.worn = true
        e.loc = wornWhere(item)
    end
    if try(item, "isFavorite", false) then e.fav = true end
    local cmax = try(item, "getConditionMax", 0)
    if cmax and cmax > 0 and item:getCondition() < cmax then e.cond = round(clamp01(item:getCondition() / cmax), 2) end
    if instanceof(item, "Food") then
        e.food = {
            hunger = round(try(item, "getHungerChange", 0) * 100, 0),
            thirst = round(try(item, "getThirstChange", 0) * 100, 0),
            rotten = try(item, "isRotten", false) or nil,
            stale = (not try(item, "isFresh", true)) or nil,
            cooked = try(item, "isCooked", false) or nil,
            burnt = try(item, "isBurnt", false) or nil,
            frozen = try(item, "isFrozen", false) or nil,
        }
    end
    if instanceof(item, "DrainableComboItem") then e.use = round(clamp01(KL.usesLeft(item) or 0), 2) end
    local fc = try(item, "getFluidContainer", nil)
    if fc then
        local cap = try(fc, "getCapacity", 0)
        if cap > 0 then
            e.fluid = { amt = round(try(fc, "getAmount", 0), 2), cap = round(cap, 2) }
            local fluid = try(fc, "getPrimaryFluid", nil)
            local fname = fluid and try(fluid, "getDisplayName", nil)
            if fname then e.fluid.n = fname end
        end
    end
    if instanceof(item, "HandWeapon") and try(item, "isRanged", false) then
        e.ammo = try(item, "getCurrentAmmoCount", 0)
        e.ammoMax = try(item, "getMaxAmmo", 0)
    end
    return e
end

-- Identical items (same type, name and state) collapse into one row with a count.
local function groupKey(e)
    if e.eq or e.worn or e.cond or e.fluid or e.ammo or e.use then return "id" .. e.id end
    local f = e.food
    local foodKey = f and ((f.rotten and "r" or "") .. (f.stale and "s" or "") .. (f.cooked and "c" or "") .. (f.burnt and "b" or "") .. (f.frozen and "f" or "")) or ""
    return e.t .. "|" .. e.n .. "|" .. foodKey
end

--- One container as screen rows; withIds also lists every item id of a collapsed row (to take them all).
local function containerEntry(player, container, name, id, withIds)
    local items = container:getItems()
    local rows, byKey = {}, {}
    for i = 0, items:size() - 1 do
        local item = items:get(i)
        -- hidden items (the bandage and wound models the game puts on the body) aren't shown, like in its inventory
        if not try(item, "isHidden", false) then
            local e = itemEntry(player, item)
            local key = groupKey(e)
            local existing = byKey[key]
            if existing then
                existing.cnt = (existing.cnt or 1) + 1
                existing.w = round(existing.w + e.w, 2)
                if withIds then
                    existing.ids = existing.ids or { existing.id }
                    table.insert(existing.ids, e.id)
                end
            else
                byKey[key] = e
                table.insert(rows, e)
            end
        end
    end
    -- held items first, then everything else by category, and what you wear at the end
    local function rank(e) return e.eq and 0 or (e.worn and 2 or 1) end
    table.sort(rows, function(a, b)
        local ra, rb = rank(a), rank(b)
        if ra ~= rb then return ra < rb end
        if a.c ~= b.c then return tostring(a.c) < tostring(b.c) end
        return tostring(a.n) < tostring(b.n)
    end)
    return {
        id = id,
        n = name,
        w = round(try(container, "getContentsWeight", 0), 1),
        cap = try(container, "getEffectiveCapacity", nil, player) or try(container, "getCapacity", 0),
        items = rows,
    }
end
KL.containerEntry = containerEntry

local lastGearSig, lastGearAt = nil, 0

--- A key ring: a container for keys only, that the game's inventory lists with your bags.
function KL.isKeyRing(item)
    if ItemType and ItemType.KEY_RING and try(item, "isItemType", false, ItemType.KEY_RING) then return true end
    if ItemTag and ItemTag.KEY_RING and try(item, "hasTag", false, ItemTag.KEY_RING) then return true end
    return try(item, "getType", "") == "KeyRing"   -- Build 41
end

--- The bags the game's inventory shows next to your own (ISInventoryPage.refreshBackpacks): the ones you
--- wear or hold, and key rings.
local function shownBag(player, item)
    if not instanceof(item, "InventoryContainer") then return false end
    return player:isEquipped(item) or try(item, "isEquipped", false) or KL.isKeyRing(item)
end

KL.addCollector("gear", 600, function(player)
    local inv = player:getInventory()
    local now = getTimestampMs()
    -- Cheap fingerprint first; the full list only goes out when it changed (or every few seconds,
    -- for slow changes like food going stale or a weapon wearing down).
    local items = inv:getItems()
    local sig = tostring(items:size()) .. ":" .. tostring(round(try(inv, "getContentsWeight", 0), 2))
    local p, s = player:getPrimaryHandItem(), player:getSecondaryHandItem()
    sig = sig .. ":" .. (p and p:getID() or 0) .. ":" .. (s and s:getID() or 0)
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        if instanceof(it, "InventoryContainer") then
            sig = sig .. ":" .. it:getItemContainer():getItems():size() .. "/" .. round(it:getItemContainer():getContentsWeight(), 2)
        end
    end
    if sig == lastGearSig and now - lastGearAt < 4000 then return nil end
    lastGearSig, lastGearAt = sig, now

    -- with every item id of a stack, so a screen can move them all; each with the game's own picture for it
    -- (the inventory button's, or the bag's own icon)
    local main = containerEntry(player, inv, "Inventory", 0, true)
    main.ic = "Icon_InventoryBasic"
    local containers = { main }
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        if shownBag(player, it) then
            local e = containerEntry(player, it:getItemContainer(), it:getDisplayName(), it:getID(), true)
            e.ic = KL.iconOf(it)
            if KL.isKeyRing(it) then e.ring = true end
            table.insert(containers, e)
        end
    end
    return { containers = containers }
end)

-- ----------------------------------------------------------------------------- skills

KL.addCollector("skills", 2000, function(player)
    local groups, order = {}, {}
    local xp = player:getXp()
    for i = 0, PerkFactory.PerkList:size() - 1 do
        local perk = PerkFactory.PerkList:get(i)
        local parent = perk:getParent()
        if parent ~= Perks.None then
            local ptype = perk:getType()
            local level = player:getPerkLevel(ptype)
            local progress = 1
            if level < 10 then
                local need = perk:getXpForLevel(level + 1)
                local have = xp:getXP(ptype) - ISSkillProgressBar.getPreviousXpLvl(perk, level)
                progress = need > 0 and clamp01(have / need) or 0
            end
            local gname = PerkFactory.getPerkName and PerkFactory.getPerkName(parent) or tostring(parent)
            if not groups[gname] then
                groups[gname] = {}
                table.insert(order, gname)
            end
            local entry = { n = perk:getName(), lvl = level, p = round(progress, 3) }
            local mult = try(xp, "getMultiplier", 0, ptype)
            if mult and mult > 0 then entry.mult = round(mult, 2) end
            local boost = try(xp, "getPerkBoost", 0, ptype)
            if boost and boost > 0 then entry.boost = boost end
            table.insert(groups[gname], entry)
        end
    end
    local out = {}
    for _, g in ipairs(order) do table.insert(out, { g = g, s = groups[g] }) end
    return { groups = out }
end)
