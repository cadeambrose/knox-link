--[[
    Knox Link — what the character looks like and carries on them.

    outfit: every worn item with the body parts it covers and the colour the game draws it in,
            skin and hair colour, the game's own protection per body part (Health > Protection),
            hotbar attachments, and every weapon carried, for a quick swap.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"
require "KnoxLink/KnoxLink_Commands"

local KL = KnoxLink
local try, round = KL.try, KL.round

local function byte(v)
    v = math.floor((tonumber(v) or 0) * 255 + 0.5)
    if v < 0 then return 0 end
    if v > 255 then return 255 end
    return v
end

local function hex(c)
    if not c then return nil end
    local r = try(c, "getRedFloat", nil)
    if r == nil then return nil end
    return string.format("%02x%02x%02x", byte(r), byte(try(c, "getGreenFloat", 0)), byte(try(c, "getBlueFloat", 0)))
end

local function condition(item)
    local cmax = try(item, "getConditionMax", 0)
    if cmax and cmax > 0 then return round(KL.clamp01(item:getCondition() / cmax), 2) end
    return nil
end

local function wornEntry(wi, item)
    local e = {
        id = item:getID(),
        n = item:getDisplayName(),
        ic = KL.iconOf(item),
        loc = tostring(try(wi, "getLocation", "")),
        cond = condition(item),
    }
    -- the colour it is drawn in, when the game tints it (otherwise the screen reads it off the icon)
    local visual, ci = try(item, "getVisual", nil), try(item, "getClothingItem", nil)
    if visual and ci and try(ci, "getAllowRandomTint", false) then e.c = hex(try(visual, "getTint", nil, ci)) end
    if instanceof(item, "Clothing") then
        local parts = {}
        local covered = try(item, "getCoveredParts", nil)
        if covered then
            for k = 0, covered:size() - 1 do table.insert(parts, tostring(covered:get(k))) end
        end
        e.parts = parts
        local holes = try(item, "getHolesNumber", 0)
        if holes > 0 then e.holes = holes end
        e.bite = round(try(item, "getBiteDefense", 0), 0)
        e.scratch = round(try(item, "getScratchDefense", 0), 0)
        local wet = try(item, "getWetness", 0)
        if wet > 10 then e.wet = round(wet, 0) end
        local blood = try(item, "getBloodLevel", 0)
        if blood > 0.1 then e.blood = true end
    end
    if instanceof(item, "InventoryContainer") then e.bag = true end
    return e
end

local function weaponEntry(player, item, slots)
    local e = {
        id = item:getID(),
        n = item:getDisplayName(),
        ic = KL.iconOf(item),
        cond = condition(item),
        two = (try(item, "isTwoHandWeapon", false) or try(item, "isRequiresEquippedBothHands", false)) or nil,
        ranged = try(item, "isRanged", false) or nil,
        slot = slots[item:getID()],
    }
    if e.ranged then
        e.ammo = try(item, "getCurrentAmmoCount", 0)
        e.ammoMax = try(item, "getMaxAmmo", 0)
    end
    if player:getPrimaryHandItem() == item then e.eq = "p" elseif player:getSecondaryHandItem() == item then e.eq = "s" end
    local c = item:getContainer()
    if c and c ~= player:getInventory() then
        local owner = try(c, "getContainingItem", nil)
        e.bag = owner and owner:getDisplayName() or nil
    end
    if item:getCondition() <= 0 then e.broken = true end
    return e
end

local SKIN = { { 1, 0.91, 0.72 }, { 0.98, 0.79, 0.49 }, { 0.8, 0.65, 0.45 }, { 0.54, 0.38, 0.25 }, { 0.36, 0.25, 0.14 } }

local lastSig, lastAt = nil, 0

KL.addCollector("outfit", 700, function(player)
    local worn = player:getWornItems()
    local now = getTimestampMs()
    local items = player:getInventory():getItems()
    local p, s = player:getPrimaryHandItem(), player:getSecondaryHandItem()
    local attachedNow = try(player, "getAttachedItems", nil)
    local sig = worn:size() .. ":" .. items:size() .. ":" .. (p and p:getID() or 0) .. ":" .. (s and s:getID() or 0)
            .. ":" .. round(try(player:getInventory(), "getContentsWeight", 0), 2) .. ":" .. (attachedNow and attachedNow:size() or 0)
    if sig == lastSig and now - lastAt < 5000 then return nil end
    lastSig, lastAt = sig, now

    local out = { worn = {}, attached = {}, weapons = {}, prot = {} }
    local hv = try(player, "getHumanVisual", nil)
    if hv then
        -- the skin is one of the character creator's five tones (CharacterCreationMain.skinColors)
        local tone = SKIN[(tonumber(try(hv, "getSkinTextureIndex", -1)) or -1) + 1]
        out.skin = tone and string.format("%02x%02x%02x", byte(tone[1]), byte(tone[2]), byte(tone[3])) or hex(try(hv, "getSkinColor", nil))
        out.hair = hex(try(hv, "getHairColor", nil))
    end
    out.female = try(player, "isFemale", false)
    for i = 0, worn:size() - 1 do
        local wi = worn:get(i)
        local item = wi and wi:getItem()
        -- inner layers first, like the game; its hidden bandage and wound models belong to the body view
        if item and not try(item, "isHidden", false) then table.insert(out.worn, wornEntry(wi, item)) end
    end

    local slots = {}
    local att = try(player, "getAttachedItems", nil)
    if att then
        for i = 0, att:size() - 1 do
            local a = att:get(i)
            local item = a and a:getItem()
            if item then
                local loc = tostring(a:getLocation())
                slots[item:getID()] = getTextOrNull("IGUI_HotbarAttachment_" .. loc:gsub(" ", "")) or loc
                table.insert(out.attached, { id = item:getID(), n = item:getDisplayName(), ic = KL.iconOf(item), slot = slots[item:getID()] })
            end
        end
    end

    for _, item in ipairs(KL.allItems(player)) do
        if instanceof(item, "HandWeapon") then table.insert(out.weapons, weaponEntry(player, item, slots)) end
    end

    -- the game's hotbar: one slot per belt loop, holster or back strap you wear
    out.hotbar = {}
    local hotbar = getPlayerHotbar and getPlayerHotbar(player:getPlayerNum())
    if hotbar and hotbar.availableSlot then
        for i, slot in pairs(hotbar.availableSlot) do
            local entry = { i = i, n = getTextOrNull("IGUI_HotbarAttachment_" .. tostring(slot.slotType)) or slot.name }
            local item = hotbar.attachedItems and hotbar.attachedItems[i]
            if item then
                entry.id = item:getID()
                entry.item = item:getDisplayName()
                entry.ic = KL.iconOf(item)
                entry.cond = condition(item)
                if p == item or s == item then entry.eq = true end
            end
            table.insert(out.hotbar, entry)
        end
        table.sort(out.hotbar, function(a, b) return a.i < b.i end)
    end
    -- in hand first, then the hotbar, then the best kept ones
    table.sort(out.weapons, function(a, b)
        local ra = a.eq and 0 or (a.slot and 1 or (a.broken and 3 or 2))
        local rb = b.eq and 0 or (b.slot and 1 or (b.broken and 3 or 2))
        if ra ~= rb then return ra < rb end
        return tostring(a.n) < tostring(b.n)
    end)
    while #out.weapons > 24 do table.remove(out.weapons) end

    -- the game's own numbers from Health > Protection
    if BodyPartType and BodyPartType.MAX then
        local last = BodyPartType.ToIndex(BodyPartType.MAX) - 1
        for i = 0, last do
            local key = BodyPartType.ToString(BodyPartType.FromIndex(i))
            out.prot[key] = {
                b = round(try(player, "getBodyPartClothingDefense", 0, i, true, false), 0),
                s = round(try(player, "getBodyPartClothingDefense", 0, i, false, false), 0),
            }
        end
    end
    return out
end)

-- The live character picture (server add-on): drawn while the game draws its UI, only while a
-- screen is watching, and rebuilt when the clothes change.
if Events.OnPostUIDraw then
    Events.OnPostUIDraw.Add(function()
        if KnoxLink_avatar then KnoxLink_avatar() end
    end)
end
if Events.OnClothingUpdated then
    Events.OnClothingUpdated.Add(function(character)
        if KnoxLink_avatarRefresh and character == getPlayer() then KnoxLink_avatarRefresh() end
    end)
end

--- Uses a hotbar slot like pressing its number key: draw the weapon, or put it back.
KL.addHandler("hotbar", function(player, a)
    local hotbar = getPlayerHotbar and getPlayerHotbar(player:getPlayerNum())
    local i = tonumber(a.i)
    if not hotbar or not i or not hotbar.availableSlot or not hotbar.availableSlot[i] then return false, "No such slot" end
    local item = hotbar.attachedItems and hotbar.attachedItems[i]
    if not item then return false, "Nothing in that slot" end
    hotbar:activateSlot(i)
    KL.refresh("outfit")
    return true, item:getDisplayName()
end)

KL.addHandler("avatarTurn", function(player, a)
    if not KnoxLink_avatarAngle then return false, "Update Knox Link Server" end
    KnoxLink_avatarAngle(tonumber(a.deg) or 90)
    return true
end)

--- Puts a weapon in the hands the way the hotbar does (puts the current one back on the belt,
--- takes it out of a bag first, uses both hands when it needs them).
KL.addHandler("swap", function(player, a)
    local item = KL.findItem(player, a.id)
    if not item then return false, "That weapon is no longer on you" end
    if player:getPrimaryHandItem() == item then return true, item:getDisplayName() end
    local hotbar = getPlayerHotbar and getPlayerHotbar(player:getPlayerNum())
    if hotbar then
        hotbar:equipItem(item)
    else
        ISInventoryPaneContextMenu.equipWeapon(item, true, try(item, "isTwoHandWeapon", false), player:getPlayerNum())
    end
    KL.refresh("outfit")
    return true, item:getDisplayName()
end)
