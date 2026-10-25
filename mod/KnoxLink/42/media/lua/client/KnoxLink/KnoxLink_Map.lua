--[[
    Knox Link — the map: the player's own marks on the game's world map, and other survivors.

    Marking follows the game's map tools: a pen or pencil to draw (its colour is the ink),
    an eraser to rub a mark out, and an illiterate character can't write notes.
    The marks themselves are read and written by the server add-on (KnoxLink_mark*), which
    uses the same list the game saves with the world map.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink

-- ISWorldMapSymbols: one colour per writing tool
local INKS = {
    { id = "Pen", r = 0.129, g = 0.129, b = 0.129 },
    { id = "Pencil", r = 0.2, g = 0.2, b = 0.2 },
    { id = "RedPen", r = 0.65, g = 0.054, b = 0.054 },
    { id = "BluePen", r = 0.156, g = 0.188, b = 0.49 },
    { id = "GreenPen", r = 0.06, g = 0.39, b = 0.17 },
}

-- Note: a Java exception counts as a game error even inside pcall, so nothing here may throw:
-- a tag that doesn't exist comes back nil and must not be passed on.
local function hasTag(inv, tag)
    if tag == nil then return false end
    local ok, r = pcall(function() return inv:containsTagRecurse(tag) end)
    return ok and r == true
end

local function carries(inv, item)
    if ItemTag and ResourceLocation and not item:find(".", 1, true) then
        local ok, tag = pcall(function() return ItemTag.get(ResourceLocation.of(item)) end)
        if ok and hasTag(inv, tag) then return true end
    end
    local ok, r = pcall(function() return inv:containsTypeRecurse(item) end)
    return ok and r == true
end

local function illiterate(player)
    local ok, r = pcall(function() return player:hasTrait(CharacterTrait.ILLITERATE) end)
    if ok and r ~= nil then return r == true end
    ok, r = pcall(function() return player:HasTrait("Illiterate") end)
    return ok and r == true
end

--- What the player can mark the map with right now.
local function tools(player)
    local inks = {}
    if not KL.immersive() then
        for _, ink in ipairs(INKS) do table.insert(inks, ink.id) end
        return { inks = inks, erase = true, notes = true }
    end
    local inv = player:getInventory()
    for _, ink in ipairs(INKS) do
        if carries(inv, ink.id) then table.insert(inks, ink.id) end
    end
    local erase = carries(inv, "Base.Eraser") or (ItemTag ~= nil and hasTag(inv, ItemTag.ERASER))
    return { inks = inks, erase = erase, notes = not illiterate(player) }
end

local function ink(id)
    for _, i in ipairs(INKS) do
        if i.id == id then return i end
    end
    return nil
end

KL.addCollector("marks", 1000, function(player)
    if KnoxLink_marks then KnoxLink_marks(tools(player)) end
    return nil   -- the server add-on publishes the list itself, only when it changed
end)

KL.addCollector("team", 1000, function(player)
    if KnoxLink_team then KnoxLink_team() end
    return nil
end)

KL.addHandler("markAdd", function(player, a)
    if not KnoxLink_markAdd then return false, "Update Knox Link Server to mark the map" end
    local x, y = tonumber(a.x), tonumber(a.y)
    if not x or not y then return false, "Bad position" end
    local t = tools(player)
    if #t.inks == 0 then return false, "You need a pen or a pencil" end
    local chosen = nil
    for _, id in ipairs(t.inks) do
        if id == a.ink then chosen = ink(id) end
    end
    chosen = chosen or ink(t.inks[1])
    local text = a.text and tostring(a.text) or nil
    if text and not t.notes then return false, "Your character can't write" end
    local err = KnoxLink_markAdd(a.sym and tostring(a.sym) or nil, text, x, y, chosen.r, chosen.g, chosen.b)
    if err then return false, err end
    KL.refresh("marks")
    return true
end)

KL.addHandler("markRemove", function(player, a)
    if not KnoxLink_markRemove then return false, "Update Knox Link Server to mark the map" end
    if not tools(player).erase then return false, "You need an eraser" end
    local err = KnoxLink_markRemove(tonumber(a.i) or -1, tonumber(a.x) or 0, tonumber(a.y) or 0)
    if err then return false, err end
    KL.refresh("marks")
    return true
end)
