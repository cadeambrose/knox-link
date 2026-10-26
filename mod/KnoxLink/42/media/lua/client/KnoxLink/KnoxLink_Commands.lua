--[[
    Knox Link — what the screens can ask the game to do.

    Every action goes through the same functions the game's own right-click menus use, so it
    takes the same time, plays the same animation and follows the same rules (a bag item is
    moved to the main inventory first, a mask comes off to eat, and so on).
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink

local function num(player)
    return player:getPlayerNum()
end

local function needItem(player, args)
    local item = KL.findItem(player, args.id)
    if not item then return nil, "That item is no longer on you" end
    return item
end

-- ----------------------------------------------------------------------------- time

KL.addHandler("speed", function(player, a)
    if isClient() then return false, "The server controls the game speed" end
    local s = math.max(0, math.min(4, math.floor(tonumber(a.s) or 1)))
    UIManager.getSpeedControls():SetCurrentGameSpeed(s)
    return true
end)

KL.addHandler("stop", function(player, a)
    ISTimedActionQueue.clear(player)
    return true, "Stopped"
end)

-- The game's own "Lock Framerate" option (Core.setFramerate takes the option's position in the
-- Display options list: 1 uncapped, 2 = 244 ... 6 = 120, 7 = 95, 8 = 90, 10 = 60).
local FPS_INDEX = { [0] = 1, [244] = 2, [240] = 3, [165] = 4, [144] = 5, [120] = 6, [95] = 7, [90] = 8, [75] = 9,
    [60] = 10, [55] = 11, [45] = 12, [30] = 13, [24] = 14 }   -- 0: uncapped

-- Project Viewpoint takes the frame-rate option over while it runs (its own cap, kept in its settings).
function KL.viewpointCaps()
    local caps = Viewpoint and Viewpoint.FrameCaps
    if not caps then return nil end
    local ok, active = pcall(caps.active)
    return ok and active and caps or nil
end

--- The frame-rate cap now (0: uncapped), from Viewpoint when it has taken it over, else the game's option.
function KL.frameCap()
    local caps = KL.viewpointCaps()
    if caps then
        local ok, cap = pcall(caps.gameCap)
        if ok and type(cap) == "number" and cap >= 0 then return cap end
    end
    local perf = getPerformance and getPerformance()
    if not perf then return nil end
    if KL.try(perf, "isFramerateUncapped", false) then return 0 end
    return KL.try(perf, "getFramerate", nil)
end

--- Sets the frame-rate cap the way the game (or Viewpoint, when it has taken it over) keeps it.
function KL.setFrameCap(cap)
    local idx = cap and FPS_INDEX[cap]
    if not idx then return false end
    local caps = KL.viewpointCaps()
    if caps then
        caps.setGameCap(cap)
        return true
    end
    getCore():setFramerate(idx)
    pcall(function() getCore():saveOptions() end)
    return true
end

KL.addHandler("fps", function(player, a)
    local cap = tonumber(a.cap)
    if not cap or cap <= 0 or not KL.setFrameCap(cap) then return false, "Unknown frame rate" end
    -- picking a rate by hand is a custom mode, and it's the most the heat control goes up to
    if KL.perfChosen then KL.perfChosen(cap) end
    return true, cap .. " fps"
end)

-- The game's own "show the survival guide" option; turning it off also closes the guide if it's open.
KL.addHandler("guide", function(player, a)
    local show = a.show == true
    getCore():setOptionShowSurvivalGuide(show)
    pcall(function() getCore():saveOptions() end)
    if not show and SurvivalGuide and SurvivalGuide.instance and SurvivalGuide.instance:isVisible() then
        SurvivalGuide.instance:setVisible(false)
    end
    return true
end)

-- ----------------------------------------------------------------------------- 3D view

-- Project Viewpoint's camera, when that mod runs: first person, third person, or the game's own
-- top-down view. Viewpoint says which view its switch goes to next; the current one is the one before.
local VIEW_BEFORE = { third = "first", isometric = "third", first = "isometric" }

-- The View button goes between first and third person (from top-down: the 3D view, in first person).
function KL.viewState()
    local v = Viewpoint and Viewpoint.View
    if not v then return nil end
    -- Viewpoint turned its 3D view off for this session (an error, or a renderer that can't run it)
    if KnoxLink_viewpointOff and KnoxLink_viewpointOff() then return nil end
    local ok, nextView = pcall(v.next)
    if not ok or not VIEW_BEFORE[nextView] then return nil end
    local cur = VIEW_BEFORE[nextView]
    return { v = cur, n = cur == "first" and "third" or "first" }
end

-- One step toward a view. Viewpoint's switch turns the 3D view on and off; turned on, the camera is
-- whatever its "third person" setting says (its own key flips that), so first <-> third is that
-- setting. From first person the switch itself goes to third person first.
local function askView(from, to)
    if from == "isometric" or to == "isometric" or not KnoxLink_viewpointThird then
        pcall(Viewpoint.View.cycle)
    else
        local p = getPlayer()
        KnoxLink_viewpointThird(to == "third", p ~= nil and p:getVehicle() ~= nil)
    end
end

-- Viewpoint takes an ask on its next frame, so a view two steps away is asked one step at a time, each
-- once the last one shows. This runs even while the game is paused (the pause menu); an ask that
-- changed nothing in 3 s is asked again. "3d" is either camera.
local viewWanted, viewAsked, viewAskedAt, viewUntil = nil, nil, 0, 0

local function viewStep()
    local s = KL.viewState()
    local now = getTimestampMs()
    local there = s and (s.v == viewWanted or (viewWanted == "3d" and s.v ~= "isometric"))
    if not s or there or now > viewUntil then
        viewWanted = nil
        Events.OnTickEvenPaused.Remove(viewStep)
        return
    end
    if s.v ~= viewAsked or now - viewAskedAt > 3000 then
        viewAsked, viewAskedAt = s.v, now
        askView(s.v, viewWanted == "3d" and "first" or viewWanted)
    end
end

local function setView(to, withinMs)
    if not viewWanted then Events.OnTickEvenPaused.Add(viewStep) end
    viewWanted, viewAsked, viewUntil = to, nil, getTimestampMs() + withinMs
end

KL.addHandler("view", function(player, a)
    local s = KL.viewState()
    if not s then return false, "Viewpoint isn't running" end
    local to = a.to and VIEW_BEFORE[a.to] and a.to or s.n
    if to == s.v then return true end
    setView(to, 10000)
    return true
end)

-- On a handheld (Zomdroid) whose renderer runs Viewpoint, the player picked that renderer for the 3D
-- view: a save, which starts in the game's top-down view, turns it on, in the camera used last.
local startAt

local function startIn3D()
    if getTimestampMs() < startAt then return end
    -- the survival guide is up: once it's closed and the world shows
    if SurvivalGuide and SurvivalGuide.instance and SurvivalGuide.instance:isVisible() then return end
    Events.OnTickEvenPaused.Remove(startIn3D)
    local s = KL.viewState()
    -- keeps asking until the world is drawn in 3D (the load screen and "click to start" come first)
    if s and s.v == "isometric" then setView("3d", 60000) end
end

Events.OnGameStart.Add(function()
    if not KnoxLink_renderer or KnoxLink_renderer() == "" then return end
    startAt = getTimestampMs() + 1500
    Events.OnTickEvenPaused.Add(startIn3D)
end)

-- ----------------------------------------------------------------------------- items

KL.addHandler("equip", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    local primary = a.hand ~= "secondary"
    local twoHands = a.hand == "both" or (primary and KL.try(item, "isRequiresEquippedBothHands", false))
    ISInventoryPaneContextMenu.equipWeapon(item, primary, twoHands, num(player))
    return true, item:getDisplayName()
end)

KL.addHandler("unequip", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    ISInventoryPaneContextMenu.unequipItem(item, num(player))
    return true, item:getDisplayName()
end)

KL.addHandler("eat", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    if not instanceof(item, "Food") then return false, "You can't eat that" end
    local portion = tonumber(a.portion) or 1
    if portion ~= 0.25 and portion ~= 0.5 then portion = 1 end
    ISInventoryPaneContextMenu.eatItem(item, portion, num(player))
    return true, item:getDisplayName()
end)

KL.addHandler("drink", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    local portion = tonumber(a.portion) or 1
    if portion ~= 0.25 and portion ~= 0.5 then portion = 1 end
    local fc = KL.try(item, "getFluidContainer", nil)
    if fc then
        if KL.try(fc, "isEmpty", true) then return false, "It's empty" end
        ISInventoryPaneContextMenu.onDrinkFluid(item, portion, player)
        return true, item:getDisplayName()
    end
    -- Build 41: water containers without the fluid system
    if KL.try(item, "isWaterSource", false) and ISInventoryPaneContextMenu.onDrinkForThirst then
        ISInventoryPaneContextMenu.onDrinkForThirst(item, player, portion)
        return true, item:getDisplayName()
    end
    return false, "You can't drink that"
end)

KL.addHandler("read", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    if player:getVehicle() and player:getVehicle():isDriver(player) then return false, "Not while driving" end
    ISInventoryPaneContextMenu.readItem(item, num(player))
    return true, item:getDisplayName()
end)

KL.addHandler("pill", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    ISInventoryPaneContextMenu.takePill(item, num(player))
    return true, item:getDisplayName()
end)

KL.addHandler("wear", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    ISInventoryPaneContextMenu.wearItem(item, num(player))
    return true, item:getDisplayName()
end)

KL.addHandler("takeoff", function(player, a)
    local item, err = needItem(player, a)
    if not item then return false, err end
    ISTimedActionQueue.add(ISUnequipAction:new(player, item, 50))
    return true, item:getDisplayName()
end)

-- ----------------------------------------------------------------------------- first aid

local function bodyPart(player, a)
    local parts = player:getBodyDamage():getBodyParts()
    local i = tonumber(a.part)
    if not i or i < 0 or i >= parts:size() then return nil end
    return parts:get(i)
end

KL.addHandler("bandage", function(player, a)
    local bp = bodyPart(player, a)
    if not bp then return false, "Unknown body part" end
    if bp:bandaged() then
        -- A dirty bandage: take it off first, then put the fresh one on.
        ISTimedActionQueue.add(ISApplyBandage:new(player, player, nil, bp, false))
    end
    local bandage = KL.bestBandage(player)
    if not bandage then return false, "You have no bandages" end
    ISInventoryPaneContextMenu.transferIfNeeded(player, bandage)
    ISTimedActionQueue.add(ISApplyBandage:new(player, player, bandage, bp, true))
    return true, bandage:getDisplayName()
end)

KL.addHandler("unbandage", function(player, a)
    local bp = bodyPart(player, a)
    if not bp or not bp:bandaged() then return false, "No bandage there" end
    ISTimedActionQueue.add(ISApplyBandage:new(player, player, nil, bp, false))
    return true
end)

KL.addHandler("removeGlass", function(player, a)
    local bp = bodyPart(player, a)
    if not bp or not bp:haveGlass() then return false, "No glass there" end
    ISTimedActionQueue.add(ISRemoveGlass:new(player, player, bp, true))
    return true
end)

-- ----------------------------------------------------------------------------- waypoint

local arrow = nil

local function clearArrow()
    if arrow then
        pcall(function() arrow:remove() end)
        arrow = nil
    end
end

function KL.setWaypoint(player, wp)
    KL.waypoint = wp
    player:getModData().KnoxLinkWaypoint = wp
    clearArrow()
    if wp and KL.options and KL.options.arrow and KL.options.arrow:getValue() then
        local ok, a = pcall(function()
            return getWorldMarkers():addDirectionArrow(player, math.floor(wp.x), math.floor(wp.y), 0, nil, 0.95, 0.66, 0.23, 1.0)
        end)
        if ok then arrow = a end
    end
end

KL.addHandler("waypoint", function(player, a)
    local x, y = tonumber(a.x), tonumber(a.y)
    if not x or not y then return false, "Bad position" end
    KL.setWaypoint(player, { x = math.floor(x), y = math.floor(y), label = a.label and tostring(a.label) or nil })
    return true
end)

KL.addHandler("waypointClear", function(player, a)
    KL.setWaypoint(player, nil)
    return true
end)

-- Restore the waypoint from the save; drop it once the player arrives.
Events.OnGameStart.Add(function()
    local player = getPlayer()
    if not player then return end
    local wp = player:getModData().KnoxLinkWaypoint
    if type(wp) == "table" and wp.x and wp.y then KL.setWaypoint(player, wp) end
end)

Events.EveryOneMinute.Add(function()
    local player = getPlayer()
    local wp = KL.waypoint
    if not player or not wp then return end
    local dx, dy = player:getX() - wp.x, player:getY() - wp.y
    if dx * dx + dy * dy < 16 then KL.setWaypoint(player, nil) end
end)
