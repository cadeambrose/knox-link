--[[
    Knox Link — what's around you.

    The containers the game's own loot window lists right now (the floor, shelves, a car's
    trunk, a body...), with their contents, so the second screen can take from them or put
    into them. Moving items uses the game's transfer action: same time, same weight rules.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try, round = KL.try, KL.round

local function lootPage(player)
    return getPlayerLoot and getPlayerLoot(player:getPlayerNum()) or nil
end

local BODY_RADIUS = 4

--- The dead bodies within a few squares on your floor that have something on them, nearest first.
local function bodies(player)
    local out = {}
    local px, py, pz = math.floor(player:getX()), math.floor(player:getY()), math.floor(player:getZ())
    local cell = getCell()
    for y = py - BODY_RADIUS, py + BODY_RADIUS do
        for x = px - BODY_RADIUS, px + BODY_RADIUS do
            local sq = cell:getGridSquare(x, y, pz)
            local list = sq and try(sq, "getDeadBodys", nil)
            if list then
                for i = 0, list:size() - 1 do
                    local body = list:get(i)
                    local c = body and try(body, "getContainer", nil)
                    if c and c:getItems():size() > 0 then
                        local dx, dy = x + 0.5 - player:getX(), y + 0.5 - player:getY()
                        local name = try(body, "isZombie", false) and "Dead zombie" or "Dead body"
                        table.insert(out, { c = c, name = name, corpse = true, sq = sq, d = math.sqrt(dx * dx + dy * dy) })
                    end
                end
            end
        end
    end
    table.sort(out, function(a, b) return a.d < b.d end)
    return out
end

--- The loot window's containers in its own order: { {c = ItemContainer, name = "Counter", btn = ...}, ... },
--- then the bodies a few steps away it doesn't list (taking from one walks there first).
local function nearby(player)
    local page = lootPage(player)
    local out, have = {}, {}
    if page and page.backpacks then
        for _, b in ipairs(page.backpacks) do
            if b and b.inventory then
                table.insert(out, { c = b.inventory, name = b.name, btn = b })
                have[b.inventory] = true
            end
        end
    end
    for _, b in ipairs(bodies(player)) do
        if not have[b.c] then table.insert(out, b) end
    end
    return out
end

--- The square a container sits on (a shelf, a body, the floor), or nil for one you carry.
local function squareOf(player, c)
    if c:isInCharacterInventory(player) then return nil end
    local parent = try(c, "getParent", nil)
    local sq = parent and try(parent, "getSquare", nil)
    return sq or try(c, "getSourceGrid", nil)
end

--- Walks next to a container first when it's out of reach, like the game's own loot window does.
function KL.walkToContainer(player, c)
    local sq = c and squareOf(player, c)
    if not sq then return end
    local dx, dy = sq:getX() + 0.5 - player:getX(), sq:getY() + 0.5 - player:getY()
    if dx * dx + dy * dy > 2.5 and luautils and luautils.walkAdj then luautils.walkAdj(player, sq, true) end
end

local function iconPath(btn)
    local tex = btn and btn.image
    local name = tex and try(tex, "getName", nil)
    if type(name) ~= "string" then return nil end
    -- "media/ui/Container_Fridge.png" -> "ui/Container_Fridge.png"
    return (name:gsub("^media/", ""))
end

local lastSig, lastAt = nil, 0

KL.addCollector("loot", 600, function(player)
    local list = nearby(player)
    local page = lootPage(player)
    local selected = page and page.inventoryPane and page.inventoryPane.inventory or nil
    local sig = ""
    for _, e in ipairs(list) do
        sig = sig .. tostring(e.c:getType()) .. e.c:getItems():size() .. ":" .. round(try(e.c, "getContentsWeight", 0), 2) .. ";"
    end
    sig = sig .. round(try(player:getInventory(), "getContentsWeight", 0), 2) .. "@" .. math.floor(player:getX()) .. "," .. math.floor(player:getY())
    local now = getTimestampMs()
    if sig == lastSig and now - lastAt < 4000 then return nil end
    lastSig, lastAt = sig, now

    local out = {}
    for i, e in ipairs(list) do
        local entry = KL.containerEntry(player, e.c, e.name or tostring(e.c:getType()), i, true)
        entry.k = i
        entry.type = tostring(e.c:getType())
        entry.ic = iconPath(e.btn)
        entry.cap = (e.btn and e.btn.capacity) or entry.cap
        if e.c == selected then entry.sel = true end
        if entry.type == "floor" then entry.floor = true end
        if e.corpse then
            entry.corpse = true
            entry.d = round(e.d, 1)
        end
        -- items in it don't come with "your item" actions
        for _, row in ipairs(entry.items) do row.a = nil end
        table.insert(out, entry)
    end
    -- the things around you with a menu of their own (doors, windows, curtains, furniture...)
    local objs = KL.nearbyObjects and KL.nearbyObjects(player) or nil
    return { list = out, objs = objs }
end)

local function findNearby(player, id)
    id = tonumber(id)
    if not id then return nil end
    for _, e in ipairs(nearby(player)) do
        local items = e.c:getItems()
        for i = 0, items:size() - 1 do
            local it = items:get(i)
            if it:getID() == id then return it, e.c end
        end
    end
    return nil
end

KL.findAround = findNearby

--- A container around you by the loot list's "near:<k>", or the floor's for "floor".
function KL.nearContainer(player, to)
    local list = nearby(player)
    if to == "floor" then
        for _, e in ipairs(list) do
            if tostring(e.c:getType()) == "floor" then return e.c end
        end
        return nil
    end
    local e = list[tonumber(tostring(to):sub(6)) or 0]
    return e and e.c or nil
end

local function transfer(player, item, src, dest)
    ISTimedActionQueue.add(ISInventoryTransferUtil.newInventoryTransferAction(player, item, src, dest))
end

--- Take one item, or every item of a stack (ids), into the main inventory.
KL.addHandler("take", function(player, a)
    local ids = type(a.ids) == "table" and a.ids or { a.id }
    local inv = player:getInventory()
    local taken, name = 0, nil
    for _, id in ipairs(ids) do
        local item, src = findNearby(player, id)
        if item then
            if not try(inv, "hasRoomFor", true, player, item) then
                if taken == 0 then return false, "That's too heavy to carry" end
                break
            end
            if taken == 0 then KL.walkToContainer(player, src) end   -- a body a few steps away
            transfer(player, item, src, inv)
            taken = taken + 1
            name = name or item:getDisplayName()
        end
    end
    if taken == 0 then return false, "It's not there anymore" end
    return true, taken > 1 and (name .. " ×" .. taken) or name
end)

--- Take everything from one of the containers (k = its place in the list), as much as fits.
KL.addHandler("takeAll", function(player, a)
    local e = nearby(player)[tonumber(a.k) or 0]
    if not e then return false, "It's not there anymore" end
    local inv = player:getInventory()
    local items, taken, full = e.c:getItems(), 0, false
    local all = {}
    for i = 0, items:size() - 1 do table.insert(all, items:get(i)) end
    KL.walkToContainer(player, e.c)
    for _, item in ipairs(all) do
        if try(inv, "hasRoomFor", true, player, item) then
            transfer(player, item, e.c, inv)
            taken = taken + 1
        else
            full = true
        end
    end
    if taken == 0 then return false, full and "You can't carry any more" or "It's empty" end
    return true, full and (taken .. " items · you're full") or (taken .. " items")
end)

--- Put an item you carry into one of the containers around you (or on the floor).
KL.addHandler("put", function(player, a)
    local item = KL.findItem(player, a.id)
    if not item then return false, "That item is no longer on you" end
    if player:isEquipped(item) and not (player:getPrimaryHandItem() == item or player:getSecondaryHandItem() == item) then
        return false, "Take it off first"
    end
    local e = nearby(player)[tonumber(a.k) or 0]
    if not e then return false, "It's not there anymore" end
    if not try(e.c, "hasRoomFor", true, player, item) then return false, "It doesn't fit" end
    if player:getPrimaryHandItem() == item or player:getSecondaryHandItem() == item then
        ISTimedActionQueue.add(ISUnequipAction:new(player, item, 50))
    end
    KL.walkToContainer(player, e.c)
    transfer(player, item, item:getContainer(), e.c)
    return true, item:getDisplayName()
end)
