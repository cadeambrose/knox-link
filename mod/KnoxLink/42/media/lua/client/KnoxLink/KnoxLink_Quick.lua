--[[
    Knox Link — one-tap actions.

    The screen says what the player wants ("eat something", "bandage me") and this picks the
    item the way a careful player would, then runs the same game function as the right-click
    menu. Nothing here can do what the player could not do by hand.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"
require "KnoxLink/KnoxLink_Commands"

local KL = KnoxLink
local try = KL.try

local function num(player)
    return player:getPlayerNum()
end

local function moodle(player, key)
    if not (MoodleType and MoodleType[key]) then return 0 end
    local ok, lvl = pcall(function() return player:getMoodles():getMoodleLevel(MoodleType[key]) end)
    return ok and type(lvl) == "number" and lvl or 0
end

--- 0..1 hunger or thirst, on any build.
local function need(player, key, legacy)
    local stats = player:getStats()
    if CharacterStat and CharacterStat[key] then
        local ok, v = pcall(function() return stats:get(CharacterStat[key]) end)
        if ok and type(v) == "number" then return v end
    end
    return try(stats, legacy, 0)
end

local function busyDriving(player)
    local v = player:getVehicle()
    return v and v:isDriver(player) and math.abs(try(v, "getCurrentSpeedKmHour", 0)) > 1
end

-- ----------------------------------------------------------------------------- bandage

-- How badly a body part needs a (new) bandage; 0 = it doesn't.
local function woundScore(bp)
    local bandaged = try(bp, "bandaged", false)
    if bandaged and not try(bp, "isBandageDirty", false) then return 0 end
    local s = 0
    if try(bp, "bleeding", false) then s = s + 100 end
    if try(bp, "deepWounded", false) then s = s + 40 end
    if try(bp, "bitten", false) then s = s + 30 end
    if try(bp, "isCut", false) then s = s + 20 end
    if try(bp, "scratched", false) then s = s + 10 end
    if try(bp, "getBurnTime", 0) > 0 then s = s + 10 end
    if s > 0 and bandaged then s = s + 5 end   -- a dirty bandage on a wound
    return s
end

local function quickBandage(player)
    local parts = player:getBodyDamage():getBodyParts()
    local best, bestScore = nil, 0
    for i = 0, parts:size() - 1 do
        local s = woundScore(parts:get(i))
        if s > bestScore then best, bestScore = i, s end
    end
    if not best then return false, "No wounds need a bandage" end
    if not KL.bestBandage(player) then return false, "You have no bandages" end
    local ok, msg = KL.handlers.bandage(player, { part = best })
    if ok then
        local bp = parts:get(best)
        local okName, name = pcall(BodyPartType.getDisplayName, bp:getType())
        return true, okName and name or msg
    end
    return ok, msg
end

-- ----------------------------------------------------------------------------- eat

local function edible(item)
    if try(item, "getCategory", "") ~= "Food" then return false end
    local script = try(item, "getScriptItem", nil)
    if script and try(script, "isCantEat", false) then return false end
    if try(item, "getCustomMenuOption", nil) then return false end          -- "Smoke", "Drink", ...
    if try(item, "getOpeningRecipe", nil) then return false end              -- still in its can
    if try(item, "getHungChange", 0) >= 0 then return false end
    if try(item, "isRotten", false) or try(item, "isFrozen", false) then return false end
    if try(item, "isbDangerousUncooked", false) and not try(item, "isCooked", false) and not try(item, "isBurnt", false) then
        return false
    end
    if try(item, "isSpice", false) then return false end
    return true
end

-- Eat what would go off first; a stale item before a fresh one, burnt food last.
local function foodScore(item)
    local s = 0
    if not try(item, "isFresh", true) then
        s = s + 50
    else
        local off = try(item, "getOffAge", 0)
        if off and off > 0 and off < 1000000 then s = s + 30 * KL.clamp01(try(item, "getAge", 0) / off) end
    end
    if try(item, "isBurnt", false) then s = s - 20 end
    if try(item, "getUnhappyChange", 0) > 0 then s = s - 5 end
    return s
end

local function quickEat(player)
    if moodle(player, "FOOD_EATEN") >= 3 then return false, "You're too full to eat" end
    local best, bestScore = nil, -1000
    for _, it in ipairs(KL.allItems(player)) do
        if edible(it) then
            local s = foodScore(it)
            if s > bestScore then best, bestScore = it, s end
        end
    end
    if not best then return false, "Nothing safe to eat" end
    -- Only as much as needed (the game's own portion rules decide what is allowed).
    local hunger = need(player, "HUNGER", "getHunger")
    local change = math.abs(try(best, "getHungerChange", 0)) * 100
    local base = math.abs(try(best, "getBaseHunger", 0)) * 100
    local need = hunger * 100
    local portion = 1
    if change >= 4 and change >= base / 4 and change * 0.25 >= need then
        portion = 0.25
    elseif change >= 2 and change >= base / 2 and change * 0.5 >= need then
        portion = 0.5
    end
    return KL.handlers.eat(player, { id = best:getID(), portion = portion })
end

-- ----------------------------------------------------------------------------- drink

-- 2 = clean water, 1 = another drink, 0 = not something to drink without thinking twice
local function drinkRank(item)
    local fc = try(item, "getFluidContainer", nil)
    if fc then
        if try(fc, "isEmpty", true) then return 0 end
        local fluid = try(fc, "getPrimaryFluid", nil)
        if not fluid then return 0 end
        local ft = try(fluid, "getFluidType", nil)
        if FluidType and (ft == FluidType.Bleach or ft == FluidType.TaintedWater) then return 0 end
        if try(fc, "isTainted", false) or try(fc, "isPoisonous", false) then return 0 end
        if FluidType and ft == FluidType.Water then return 2 end
        if not (FluidCategory and FluidCategory.Beverage) then return 0 end
        local ok, bev = pcall(function() return fluid:isCategory(FluidCategory.Beverage) end)
        if ok and bev then
            if FluidCategory.Alcoholic then
                local okA, alcohol = pcall(function() return fluid:isCategory(FluidCategory.Alcoholic) end)
                if okA and alcohol then return 0 end
            end
            return 1
        end
        return 0
    end
    -- Build 41
    if try(item, "isWaterSource", false) and (KL.usesLeft(item) or 0) > 0 then
        return try(item, "isTaintedWater", false) and 0 or 2
    end
    return 0
end

local function quickDrink(player)
    local best, bestRank = nil, 0
    for _, it in ipairs(KL.allItems(player)) do
        local r = drinkRank(it)
        if r > bestRank then best, bestRank = it, r end
    end
    if not best then return false, "Nothing safe to drink" end
    local thirst = need(player, "THIRST", "getThirst")
    local portion = thirst <= 0.25 and 0.25 or (thirst <= 0.5 and 0.5 or 1)
    return KL.handlers.drink(player, { id = best:getID(), portion = portion })
end

-- ----------------------------------------------------------------------------- pills

local function quickPills(player)
    for _, it in ipairs(KL.allItems(player)) do
        if it:getType() == "Pills" and (KL.usesLeft(it) or 1) > 0 then
            return KL.handlers.pill(player, { id = it:getID() })
        end
    end
    return false, "You have no painkillers"
end

-- ----------------------------------------------------------------------------- light

local function isLight(item)
    if not item or not try(item, "canBeActivated", false) then return false end
    if instanceof(item, "Drainable") or instanceof(item, "DrainableComboItem") then
        if (KL.usesLeft(item) or 0) <= 0 then return false end
    end
    local t = item:getType()
    if t == "CandleLit" or t == "Lantern_HurricaneLit" then return false end
    return try(item, "getLightStrength", 0) > 0 or try(item, "getDisplayCategory", "") == "LightSource"
end

local pendingLight = nil

local function quickLight(player)
    for _, it in ipairs({ player:getPrimaryHandItem(), player:getSecondaryHandItem() }) do
        if isLight(it) then
            ISInventoryPaneContextMenu.onActivateItem(it, num(player))
            return true, it:isActivated() and "On" or "Off"
        end
    end
    -- a worn headlamp
    local worn = try(player, "getWornItems", nil)
    if worn then
        for i = 0, worn:size() - 1 do
            local it = worn:getItemByIndex(i)
            if isLight(it) then
                ISInventoryPaneContextMenu.onActivateItem(it, num(player))
                return true, it:isActivated() and "On" or "Off"
            end
        end
    end
    for _, it in ipairs(KL.allItems(player)) do
        if isLight(it) and not try(it, "isWorn", false) then
            ISInventoryPaneContextMenu.equipWeapon(it, false, false, num(player))
            pendingLight = { item = it, untilMs = getTimestampMs() + 8000 }
            return true, it:getDisplayName()
        end
    end
    return false, "You have no working light"
end

-- Turn the light on once it is actually in the hand.
Events.OnTick.Add(function()
    if not pendingLight then return end
    local player = getPlayer()
    if not player or getTimestampMs() > pendingLight.untilMs then
        pendingLight = nil
        return
    end
    local it = pendingLight.item
    if player:getSecondaryHandItem() == it or player:getPrimaryHandItem() == it then
        if not it:isActivated() then ISInventoryPaneContextMenu.onActivateItem(it, num(player)) end
        pendingLight = nil
    end
end)

-- ----------------------------------------------------------------------------- weapon

local function weaponScore(w)
    if not instanceof(w, "HandWeapon") or try(w, "isRanged", false) then return -1 end
    if w:getCondition() <= 0 then return -1 end
    local cmax = try(w, "getConditionMax", 1)
    local dmg = (try(w, "getMinDamage", 0) + try(w, "getMaxDamage", 0)) / 2
    if dmg <= 0 then return -1 end
    return dmg * (0.5 + 0.5 * KL.clamp01(w:getCondition() / math.max(1, cmax)))
end

local function quickWeapon(player)
    local current = player:getPrimaryHandItem()
    local best, bestScore = nil, weaponScore(current) + 0.05
    for _, it in ipairs(KL.allItems(player)) do
        if it ~= current then
            local s = weaponScore(it)
            if s > bestScore then best, bestScore = it, s end
        end
    end
    if not best then
        if weaponScore(current) > 0 then return false, "Already holding your best weapon" end
        return false, "You have no weapon"
    end
    return KL.handlers.equip(player, { id = best:getID(), hand = "primary" })
end

-- ----------------------------------------------------------------------------- sit

local function quickSit(player)
    if player:getVehicle() then return false, "Not in a vehicle" end
    local sitting = try(player, "isSitOnGround", nil)
    if sitting == nil then sitting = try(player, "isSitting", false) end
    if sitting then
        ISTimedActionQueue.clear(player)
        player:setVariable("forceGetUp", true)
        return true, "Standing up"
    end
    try(player, "setAutoWalk", nil, false)
    player:reportEvent("EventSitOnGround")
    return true, "Sitting down"
end

-- ----------------------------------------------------------------------------- sleep

local function isBed(o)
    local props = o and try(o, "getProperties", nil)
    if not props or not IsoFlagType or not IsoFlagType.bed then return false end
    local v = try(props, "has", nil, IsoFlagType.bed)
    if v == nil then v = try(props, "Is", false, IsoFlagType.bed) end
    return v == true
end

--- The closest bed on this floor (in the same building when indoors), or nil.
function KL.nearestBed(player, radius)
    local here = player:getCurrentSquare()
    if not here then return nil end
    local building = here:getBuilding()
    local px, py, pz = math.floor(player:getX()), math.floor(player:getY()), math.floor(player:getZ())
    local best, bestD = nil, 999
    local cell = getCell()
    for y = py - radius, py + radius do
        for x = px - radius, px + radius do
            local sq = cell:getGridSquare(x, y, pz)
            if sq and (building == nil or sq:getBuilding() == building) then
                local objs = sq:getObjects()
                for i = 0, objs:size() - 1 do
                    if isBed(objs:get(i)) then
                        local d = math.abs(x - px) + math.abs(y - py)
                        if d < bestD then best, bestD = objs:get(i), d end
                    end
                end
            end
        end
    end
    return best
end

-- the same flow as choosing "Sleep" on a bed and answering yes (it walks there and checks it's safe)
local function quickSleep(player)
    if player:getVehicle() then return false, "Get out of the vehicle first" end
    local bed = KL.nearestBed(player, 8)
    if not bed then return false, "There's no bed near you" end
    ISWorldObjectContextMenu.onConfirmSleep(nil, { internal = "YES" }, player:getPlayerNum(), bed)
    return true, "Going to bed"
end

local QUICK = {
    bandage = quickBandage, eat = quickEat, drink = quickDrink, pills = quickPills,
    light = quickLight, weapon = quickWeapon, sit = quickSit, sleep = quickSleep,
}

--- Which of the item actions the character has something for, so a screen only offers those.
function KL.quickAvailable(player)
    local can = { bandage = KL.bestBandage(player) ~= nil, eat = false, drink = false, pills = false }
    for _, it in ipairs(KL.allItems(player)) do
        if not can.eat and edible(it) then can.eat = true end
        if not can.drink and drinkRank(it) > 0 then can.drink = true end
        if not can.pills and it:getType() == "Pills" and (KL.usesLeft(it) or 1) > 0 then can.pills = true end
    end
    return can
end

KL.addHandler("quick", function(player, a)
    local fn = QUICK[tostring(a.a)]
    if not fn then return false, "Unknown action" end
    if a.a ~= "light" and a.a ~= "bandage" and busyDriving(player) then return false, "Not while driving" end
    return fn(player)
end)

-- ----------------------------------------------------------------------------- vehicle

KL.addHandler("car", function(player, a)
    local v = player:getVehicle()
    if not v then return false, "You're not in a vehicle" end
    local what = tostring(a.a)
    if what == "horn" then
        if not v:isDriver(player) then return false, "Only the driver can do that" end
        ISVehicleMenu.onHorn(player)
        return true
    elseif what == "lights" then
        if not try(v, "hasHeadlights", false) then return false, "This vehicle has no headlights" end
        if not v:isDriver(player) then return false, "Only the driver can do that" end
        local wasOn = v:getHeadlightsOn()
        ISVehicleMenu.onToggleHeadlights(player)
        return true, wasOn and "Off" or "On"
    elseif what == "heater" then
        local heater = v:getPartById("Heater")
        if not heater then return false, "This vehicle has no heater" end
        local md = heater:getModData()
        local on = not (md.active == true)
        sendClientCommand(player, "vehicle", "toggleHeater", { on = on, temp = md.temperature or 15 })
        player:playSound("VehicleACButton")
        return true, on and "On" or "Off"
    elseif what == "engine" then
        if not v:isDriver(player) then return false, "Only the driver can do that" end
        if v:isEngineRunning() or try(v, "isEngineStarted", false) then
            ISVehicleMenu.onShutOff(player)
            return true, "Engine off"
        end
        if not (try(v, "isKeysInIgnition", false) or try(v, "isHotwired", false)) then
            return false, "There's no key in the ignition"
        end
        if not try(v, "isEngineWorking", true) then return false, "The engine is broken" end
        ISVehicleMenu.onStartEngine(player)
        return true, "Starting"
    end
    return false, "Unknown control"
end)
