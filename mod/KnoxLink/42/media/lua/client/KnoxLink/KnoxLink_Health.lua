--[[
    Knox Link — the game's own health panel, for one body part at a time.

    What is wrong with it is said the way the game's health panel says it (its lines, colours and
    what a doctor of this skill would notice), and the treatments are the ones its right-click
    menu offers there: that menu is built by the game's own code, recorded instead of shown, and
    the chosen option runs through the game's own handler. So a treatment only shows up when the
    character has what it takes (here or in a container next to them), in the game's order of
    rules, and takes the time and animation it does in the game.
]]

require "KnoxLink/KnoxLink_Core"

local KL = KnoxLink

local call = KL.try

local function doctorLevel(player)
    local ok, lvl = pcall(function() return player:getPerkLevel(Perks.Doctor) end)
    return ok and lvl or 0
end

-- ----------------------------------------------------------------------------- what's wrong

--- The lines the game's health panel lists for a body part (ISHealthPanel's drawing code): text in
--- the game's words, and how it colours them (bad, warn, good).
function KL.woundLines(player, bp)
    local doctor = doctorLevel(player)
    local lines = {}
    local function add(key, kind, extra)
        table.insert(lines, { t = getText(key) .. (extra or ""), k = kind })
    end
    -- a doctor can tell how bad a wound is
    local function severity(knows, time, severe, moderate)
        if not knows then return nil end
        if time > severe then return " (" .. getText("IGUI_health_Severe") .. ")" end
        if time > moderate then return " (" .. getText("IGUI_health_Moderate") .. ")" end
        return nil
    end
    local bandaged = call(bp, "bandaged", false)
    if call(bp, "getPlantainFactor", 0) > 0 then add("ContextMenu_PlantainCataplasm", "good") end
    if call(bp, "getComfreyFactor", 0) > 0 then add("ContextMenu_ComfreyCataplasm", "good") end
    if call(bp, "getGarlicFactor", 0) > 0 then add("ContextMenu_GarlicCataplasm", "good") end
    if call(bp, "scratched", false) then add("IGUI_health_Scratched", "bad", severity(doctor > 2, call(bp, "getScratchTime", 0), 17, 14)) end
    if call(bp, "isCut", false) then add("IGUI_health_Cut", "bad", severity(doctor > 2, call(bp, "getCutTime", 0), 17, 14)) end
    if call(bp, "deepWounded", false) then add("IGUI_health_DeepWound", "bad", severity(doctor > 4, call(bp, "getDeepWoundTime", 0), 10, 8)) end
    if call(bp, "bitten", false) then add("IGUI_health_Bitten", "bad") end
    local pain = call(bp, "getAdditionalPain", 0)
    if pain > 10 then add(pain > 50 and "IGUI_health_HeavyPain" or "IGUI_health_Pain", "bad") end
    local stiff = call(bp, "getStiffness", 0)
    if stiff >= 5 then
        if stiff < 20 then add("IGUI_health_MinorStiffness", "warn") else add("IGUI_health_Stiffness", "bad") end
    end
    if call(bp, "bleeding", false) then add("IGUI_health_Bleeding", "bad") end
    local splint = call(bp, "getSplintFactor", 0)
    if call(bp, "getFractureTime", 0) > 0 and splint == 0 then
        add("IGUI_health_Fracture", "bad", severity(doctor > 6, call(bp, "getFractureTime", 0), 50, 20))
    end
    if splint > 0 then
        local extra = nil
        if doctor > 4 then
            if splint > 4 then extra = " (" .. getText("IGUI_health_Good") .. ")"
            elseif call(bp, "getFractureTime", 0) > 2 then extra = " (" .. getText("IGUI_health_Moderate") .. ")" end
        end
        add("IGUI_health_Splinted", "good", extra)
    end
    if bandaged then
        if call(bp, "getBandageLife", 0) > 0 then add("IGUI_health_Bandaged", "good") else add("IGUI_health_DirtyBandage", "warn") end
    end
    -- only a good doctor notices an infection early
    if call(bp, "isInfectedWound", false) and not bandaged
            and (doctor > 8 or call(bp, "getWoundInfectionLevel", 0) * 10 >= (2.5 - doctor)) then
        add("IGUI_health_Infected", "warn")
    end
    if call(bp, "haveBullet", false) and not bandaged then add("IGUI_health_LodgedBullet", "bad") end
    if call(bp, "getBurnTime", 0) > 0 and not bandaged then
        local extra = (doctor > 4 and call(bp, "isNeedBurnWash", false)) and (" (" .. getText("IGUI_health_NeedCleaning") .. ")") or nil
        add("IGUI_health_Burned", "bad", extra)
    end
    if call(bp, "stitched", false) then
        local extra = doctor > 6 and (" (" .. getText(call(bp, "getStitchTime", 0) > 40 and "IGUI_health_Good" or "IGUI_health_NeedTime") .. ")") or nil
        add("IGUI_health_Stitched", "good", extra)
    end
    if call(bp, "haveGlass", false) and not bandaged then add("IGUI_health_LodgedGlassShards", "bad") end
    -- disinfected recently (the game keeps it as the wound's alcohol level; its panel doesn't list it)
    if call(bp, "getAlcoholLevel", 0) > 0 then table.insert(lines, { t = "Disinfected", k = "good" }) end
    return lines
end

-- ----------------------------------------------------------------------------- treatments

-- The game's health panel menu for a body part of the player, recorded instead of shown
-- (KL.recordMenu, KnoxLink_Menus).
local function healthMenu(player, bp)
    if not (ISHealthPanel and ISHealthPanel.doBodyPartContextMenu and KL.recordMenu) then return nil end
    local panel = setmetatable({ character = player, doctorLevel = doctorLevel(player), actions = {} }, { __index = ISHealthPanel })
    panel.getAbsoluteX = function() return 0 end
    panel.getAbsoluteY = function() return 0 end
    return KL.recordMenu(player:getPlayerNum(), function()
        ISHealthPanel.doBodyPartContextMenu(panel, bp, 0, 0)
    end)
end

-- The order a careful player treats a wound in: what's in it out, clean it, close it, then cover it.
local ORDER = {
    "ContextMenu_Remove_Glass", "ContextMenu_Remove_Bullet", "ContextMenu_Remove_Bandage", "ContextMenu_Disinfect",
    "ContextMenu_Clean_Burn", "ContextMenu_Stitch", "ContextMenu_PlantainCataplasm", "ContextMenu_ComfreyCataplasm",
    "ContextMenu_GarlicCataplasm", "ContextMenu_Bandage", "ContextMenu_Splint", "ContextMenu_Remove_Stitch",
    "ContextMenu_Remove_Splint",
}

local function rank(name)
    for i, key in ipairs(ORDER) do
        if name == getText(key) then return i end
    end
    return #ORDER + 1
end

--- The treatments the game offers for a body part right now: { a = what, i = with which item or nil,
--- ic = that item's icon, opt = the recorded option }, in treating order.
function KL.treatments(player, bp)
    local rec = healthMenu(player, bp)
    if not rec then return {} end
    local out = {}
    local cheat = getText("ContextMenu_Cheat")
    for _, o in ipairs(rec.options) do
        if o.name ~= cheat then
            if o.sub then
                for _, s in ipairs(o.sub.options) do
                    if s.onSelect then
                        table.insert(out, { a = o.name, i = s.name, ic = s.itemForTexture and KL.iconOf(s.itemForTexture) or nil, opt = s, r = rank(o.name) })
                    end
                end
            elseif o.onSelect then
                table.insert(out, { a = o.name, ic = o.itemForTexture and KL.iconOf(o.itemForTexture) or nil, opt = o, r = rank(o.name) })
            end
        end
    end
    -- stable: the game's own order within a kind
    for i, t in ipairs(out) do t.n = i end
    table.sort(out, function(x, y)
        if x.r ~= y.r then return x.r < y.r end
        return x.n < y.n
    end)
    return out
end

local function bodyPart(player, i)
    local parts = player:getBodyDamage():getBodyParts()
    i = tonumber(i)
    if not i or i < 0 or i >= parts:size() then return nil end
    return parts:get(i)
end

-- The body part a screen has open: its lines and treatments go out with the body state.
KL.healthPart = nil

KL.addHandler("healthPart", function(player, a)
    KL.healthPart = tonumber(a.part)
    KL.treatDirty = true
    KL.refresh("body")
    return true
end)

KL.addHandler("treat", function(player, a)
    local bp = bodyPart(player, a.part)
    if not bp then return false, "Unknown body part" end
    for _, t in ipairs(KL.treatments(player, bp)) do
        if t.a == a.a and (t.i or "") == (a.i or "") then
            local o = t.opt
            o.onSelect(o.target, unpack(o.args, 1, o.nargs))
            KL.treatDirty = true
            return true, t.i
        end
    end
    return false, "You can't do that now"
end)
