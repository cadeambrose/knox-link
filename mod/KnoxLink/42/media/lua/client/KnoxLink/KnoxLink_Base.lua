--[[
    Knox Link — your base.

    The player marks a spot as their base. While they are there, Knox Link looks at what the
    character can see of it: generators, rain collectors and crops (in the words the game's own
    info windows use). Away from it, the screen shows what they saw last time, and how many
    hours ago that was.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try = KL.try

local RADIUS = 20   -- squares around the mark that belong to the base
local NEAR = 30     -- how close the player must be to see the base

local function baseOf(player)
    local b = player:getModData().KnoxLinkBase
    if type(b) == "table" and b.x and b.y then return b end
    return nil
end

--- The base for the screens: the player's mark, or their multiplayer safehouse.
function KL.basePos(player)
    local b = baseOf(player)
    if b then return { x = b.x, y = b.y, n = b.n } end
    if isClient() and SafeHouse then
        local ok, sh = pcall(function() return SafeHouse.hasSafehouse(player) end)
        if ok and sh then
            return {
                x = math.floor(sh:getX() + sh:getW() / 2), y = math.floor(sh:getY() + sh:getH() / 2),
                n = try(sh, "getTitle", nil), safehouse = true,
            }
        end
    end
    return nil
end

local function propName(o)
    local props = try(o, "getProperties", nil)
    if not props then return nil end
    return try(props, "get", nil, "CustomName") or try(props, "Val", nil, "CustomName")
end

local function waterWord(lvl)
    if lvl > 80 then return getText("Farming_Well_watered") end
    if lvl > 60 then return getText("Farming_Fine") end
    if lvl > 50 then return getText("Farming_Thirsty") end
    if lvl > 25 then return getText("Farming_Dry") end
    return getText("Farming_Parched")
end

local function healthWord(h)
    if h > 80 then return getText("Farming_Flourishing") end
    if h > 60 then return getText("Farming_Verdant") end
    if h >= 50 then return getText("Farming_Healthy") end
    if h >= 25 then return getText("Farming_Sickly") end
    return getText("Farming_Dying")
end

local function plantInfo(plant)
    local alive = plant.isAlive and plant:isAlive()
    local name = getTextOrNull("Farming_" .. tostring(plant.typeOfSeed)) or tostring(plant.typeOfSeed)
    local sick = (plant.aphidLvl or 0) > 0 or (plant.fliesLvl or 0) > 0 or (plant.mildewLvl or 0) > 0 or (plant.slugsLvl or 0) > 0
    return {
        n = name,
        alive = alive and true or false,
        ready = (alive and plant.hasVegetable) and true or false,
        water = tonumber(plant.waterLvl) or 0,
        health = tonumber(plant.health) or 0,
        sick = sick,
    }
end

--- Looks around the base (only works while the player is close: the rest of the world is not loaded).
local function scan(player, base)
    local cell = getCell()
    local gens, barrels, crops = {}, { n = 0, amt = 0, cap = 0 }, {}
    local farming = CFarmingSystem and CFarmingSystem.instance
    local power = false
    for z = 0, 3 do
        for y = base.y - RADIUS, base.y + RADIUS do
            for x = base.x - RADIUS, base.x + RADIUS do
                local sq = cell:getGridSquare(x, y, z)
                if sq then
                    if not power and z == 0 and x == base.x and y == base.y then
                        power = try(sq, "haveElectricity", false) or (try(getWorld(), "isHydroPowerOn", false) and not try(sq, "isOutside", true))
                    end
                    local objs = sq:getObjects()
                    for i = 0, objs:size() - 1 do
                        local o = objs:get(i)
                        if instanceof(o, "IsoGenerator") then
                            local fuel = try(o, "getFuelPercentage", nil)
                            if fuel == nil then fuel = try(o, "getFuel", 0) end
                            table.insert(gens, {
                                fuel = KL.round(fuel, 0), cond = KL.round(try(o, "getCondition", 0), 0),
                                on = try(o, "isActivated", false), x = x, y = y,
                            })
                        elseif instanceof(o, "IsoThumpable") and propName(o) == "Rain Collector Barrel" then
                            local fc = try(o, "getFluidContainer", nil)
                            local amt, cap
                            if fc then
                                amt, cap = try(fc, "getAmount", 0), try(fc, "getCapacity", 0)
                            else
                                amt, cap = try(o, "getWaterAmount", 0), try(o, "getWaterMax", 0)   -- Build 41
                            end
                            barrels.n = barrels.n + 1
                            barrels.amt = barrels.amt + amt
                            barrels.cap = barrels.cap + cap
                        end
                    end
                    if farming and z == 0 then
                        local ok, plant = pcall(function() return farming:getLuaObjectOnSquare(sq) end)
                        if ok and plant and plant.typeOfSeed then table.insert(crops, plantInfo(plant)) end
                    end
                end
            end
        end
    end
    -- crops grouped by kind, the ones that need something first
    local byName, groups = {}, {}
    for _, c in ipairs(crops) do
        local g = byName[c.n]
        if not g then
            g = { n = c.n, cnt = 0, ready = 0, dry = 0, sick = 0, dead = 0, water = 0, health = 0 }
            byName[c.n] = g
            table.insert(groups, g)
        end
        g.cnt = g.cnt + 1
        if not c.alive then
            g.dead = g.dead + 1
        else
            if c.ready then g.ready = g.ready + 1 end
            if c.water <= 50 then g.dry = g.dry + 1 end
            if c.sick then g.sick = g.sick + 1 end
            g.water = g.water + c.water
            g.health = g.health + c.health
        end
    end
    for _, g in ipairs(groups) do
        local alive = g.cnt - g.dead
        if alive > 0 then
            g.waterWord = waterWord(g.water / alive)
            g.healthWord = healthWord(g.health / alive)
        end
        g.water, g.health = nil, nil
    end
    table.sort(groups, function(a, b)
        local na, nb = a.dry + a.sick + a.ready, b.dry + b.sick + b.ready
        if na ~= nb then return na > nb end
        return a.n < b.n
    end)
    return {
        at = KL.round(getGameTime():getWorldAgeHours(), 2),
        power = power and true or false,
        gens = gens,
        barrels = barrels.n > 0 and { n = barrels.n, pct = barrels.cap > 0 and KL.round(barrels.amt / barrels.cap, 2) or 0 } or nil,
        crops = groups,
    }
end

local function near(player, base)
    local dx, dy = player:getX() - base.x, player:getY() - base.y
    return dx * dx + dy * dy <= NEAR * NEAR
end

local function remember(player, base)
    local seen = scan(player, base)
    player:getModData().KnoxLinkBaseSeen = seen
    return seen
end

KL.addCollector("base", 3000, function(player)
    local base = KL.basePos(player)
    if not base then return { set = false } end
    local seen
    if near(player, base) then
        seen = remember(player, base)
        seen.live = true
    else
        seen = player:getModData().KnoxLinkBaseSeen
    end
    local out = { set = true, x = base.x, y = base.y, n = base.n, safehouse = base.safehouse, seen = seen }
    if seen and seen.at then
        out.ago = KL.round(getGameTime():getWorldAgeHours() - seen.at, 1)
    end
    return out
end)

-- Keep "last seen" fresh while the player is home, even with the Base tab closed.
Events.EveryTenMinutes.Add(function()
    local player = getPlayer()
    if not player then return end
    local base = KL.basePos(player)
    if base and near(player, base) then
        local ok, err = pcall(remember, player, base)
        if not ok then KL.warn("base", err) end
    end
end)

KL.addHandler("baseSet", function(player, a)
    local x, y = tonumber(a.x), tonumber(a.y)
    if not x or not y then x, y = player:getX(), player:getY() end
    local name = a.n and tostring(a.n):sub(1, 40) or nil
    player:getModData().KnoxLinkBase = { x = math.floor(x), y = math.floor(y), n = name }
    player:getModData().KnoxLinkBaseSeen = nil
    if KL.journalAdd then KL.journalAdd(player, "base", "Made this place my base.") end
    KL.refresh("base")
    return true
end)

KL.addHandler("baseClear", function(player, a)
    player:getModData().KnoxLinkBase = nil
    player:getModData().KnoxLinkBaseSeen = nil
    KL.refresh("base")
    return true
end)
