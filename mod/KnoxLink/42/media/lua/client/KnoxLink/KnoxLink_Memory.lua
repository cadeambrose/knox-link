--[[
    Knox Link — what the character remembers, and the floor plan.

    Memory ("Where did I see...?"): every container the game's loot window lists as you go (a
    shelf, a fridge, a body, a car's trunk, the floor) is noted with what's in it, by the server
    add-on (Memory.java), together with the buildings you went through, where you left your car
    and the way you walked. Kept per character in the save; a new character remembers nothing.

      seenFind {q, req}  ->  "seen" once: {req, q, total, r = { {n, c, t, b, rm, town, x, y, z, d, dir, h, body}, ... }}
      memory   (every 3 s while a screen shows the map, when it changed):
               {b = { {x, y, w, h, searched 0..1}, ... }, car = {x, y, z, n}, tr = {x, y, x, y, ...}}

    The floor plan ("plan", twice a second while a screen shows it): see Plan.java.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try = KL.try

local selected = nil
local inCar = nil
local nextAt = 0

local function select(player)
    if not (player and KnoxLink_memoryFor and KL.journalId) then return end
    local id = KL.journalId(player)
    if id and id ~= selected then
        selected = id
        KnoxLink_memoryFor(id)
    end
end

local function note(player)
    local page = getPlayerLoot and getPlayerLoot(player:getPlayerNum()) or nil
    if not (page and page.backpacks) then return end
    for _, b in ipairs(page.backpacks) do
        if b and b.inventory then KnoxLink_memorySaw(b.inventory, b.name or "") end
    end
end

Events.OnTick.Add(function()
    if not KnoxLink_memoryTick then return end
    local now = getTimestampMs()
    if now < nextAt then return end
    nextAt = now + 1000
    local player = getPlayer()
    if not player or player:isDead() then return end
    local ok, err = pcall(function()
        select(player)
        note(player)
        KnoxLink_memoryTick()
        local v = player:getVehicle()
        if v and v:isDriver(player) then inCar = v end
    end)
    if not ok then KL.warn("memory", err) end
end)

Events.OnExitVehicle.Add(function(character)
    if character ~= getPlayer() or not inCar or not KnoxLink_memoryCar then return end
    KnoxLink_memoryCar(inCar)
    inCar = nil
end)

local function save()
    if KnoxLink_memorySave then KnoxLink_memorySave() end
end

if Events.OnSave then Events.OnSave.Add(save) end
if Events.OnPostSave then Events.OnPostSave.Add(save) end

Events.OnGameStart.Add(function()
    selected = nil
    select(getPlayer())
end)

Events.OnCreatePlayer.Add(function(playerNum, player)
    if playerNum == 0 then
        selected = nil
        inCar = nil
        select(player)
    end
end)

-- ----------------------------------------------------------------------------- screens

KL.addCollector("memory", 3000, function(player)
    if KnoxLink_memoryOverlay then
        select(player)
        KnoxLink_memoryOverlay()
    end
    return nil   -- the server add-on publishes it
end)

KL.addCollector("plan", 500, function(player)
    if KnoxLink_plan then KnoxLink_plan(not KL.immersive()) end
    return nil
end)

KL.addHandler("seenFind", function(player, a)
    if not KnoxLink_memoryFind then return false, "Needs the Knox Link Server add-on" end
    select(player)
    local q = tostring(a.q or "")
    if #q > 60 then q = q:sub(1, 60) end
    KnoxLink_memoryFind(q, tonumber(a.req) or 0)
    return true
end)
