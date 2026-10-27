--[[
    Knox Link — the armoury: the character's weapons with their stats, and a gunsmith for the guns.

    Guns take the game's weapon parts in four places: an optic (red dot, tritium sights, scopes), the
    barrel (laser, gun light, choke tubes), a recoil pad and a sling (ammo straps). Which parts fit a gun
    is the game's own list (each part's MountOn); attaching and taking them off go through the game's
    own actions (the inventory's "Add/Remove weapon upgrade"), with its rules (a screwdriver).

      weapons (every 2 s while the Weapons tab is open, when it changed):
        guns, melee  { {id, n, t, ic, cond, broken, eq = "p"|"s", slot = hotbar place, stats = {...},
                        sharp, sharpMax, head, headMax (bladed and headed weapons),
                        ammo = {n, max, mag = magazine's name, chambered, spare = rounds carried, mags = loaded magazines carried},
                        slots = { {k = "Scope"|"Canon"|"RecoilPad"|"Sling", part = PART | nil,
                                   fits = { PART + {have = carried, at = where it was seen}, ... }}, ... }}, ... }
        throw        { {id, n, t, ic, cnt, kind = "lethal"|"tactical", fire, blast, smoke, noise, power}, ... }
        seen         { {t, n, ic, c, b, rm, town, x, y, z, d, dir, h}, ... }  weapons seen elsewhere, not carried
        screwdriver  true when the character has one
        max          the most each stat gets among all the game's weapons ({gun = {...}, melee = {...}}), for the bars
      PART = {id, n, t, ic, k, mods = {aim, sight, sightMin, reload, recoil, hit, range, weight, spread, dmg, optic}}
      a gun's stats: {dmg, range, hit, aim, recoil, reload, noise, sight (with its optic), sight0 (without), crit, weight, pellets, mode}

      weaponAttach {w, p}   puts part p on weapon w (taking off what's there first)
      weaponDetach {w, k}   takes the part in place k off weapon w
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try, round = KL.try, KL.round

local SLOTS = { "Scope", "Canon", "RecoilPad", "Sling" }

-- ----------------------------------------------------------------------------- the game's parts and weapons

local parts = nil   -- every weapon part in the game: {t, n, ic, k, mods, mount = {[gun type] = true}}
local maxima = nil

--- What a part changes. The game adds damage, range, reload, recoil, aiming and hit chance to the gun,
--- and an optic's sight range replaces the gun's own (HandWeapon.attachWeaponPart, getMaxSightRange).
local function modsOf(p)
    local optic = ItemTag and ItemTag.OPTICS and try(p, "hasTag", false, ItemTag.OPTICS) == true
    return {
        aim = try(p, "getAimingTime", 0), sight = try(p, "getMaxSightRange", 0), sightMin = try(p, "getMinSightRange", 0),
        reload = try(p, "getReloadTime", 0), recoil = round(try(p, "getRecoilDelay", 0), 1), hit = try(p, "getHitChance", 0),
        range = round(try(p, "getMaxRange", 0), 1), weight = round(try(p, "getWeightModifier", 0), 2),
        spread = round(try(p, "getSpreadModifier", 0), 2), dmg = round(try(p, "getDamage", 0), 2), optic = optic or nil,
    }
end

--- The game's weapon parts, read once from fresh copies of each (their MountOn lists and modifiers).
local function allParts()
    if parts then return parts end
    parts = {}
    local items = getScriptManager():getAllItems()
    for i = 0, items:size() - 1 do
        local s = items:get(i)
        if try(s, "getDisplayCategory", nil) == "WeaponPart" then
            local full = s:getFullName()
            local ok, p = pcall(instanceItem, full)
            if ok and p and instanceof(p, "WeaponPart") then
                local mount = {}
                local list = try(p, "getMountOn", nil)
                for k = 0, list and list:size() - 1 or -1 do mount[tostring(list:get(k))] = true end
                table.insert(parts, { t = full, n = p:getDisplayName(), ic = KL.iconOf(p), k = try(p, "getPartType", ""), mods = modsOf(p), mount = mount })
            end
        end
    end
    return parts
end

--- The most each stat gets among the game's weapons, so a bar shows where a weapon stands.
local function allMaxima()
    if maxima then return maxima end
    local gun = { dmg = 0, range = 0, noise = 0 }
    local melee = { dmg = 0, range = 0, crowd = 0, knock = 0, cond = 0, endure = 0 }
    local items = getScriptManager():getAllItems()
    for i = 0, items:size() - 1 do
        local s = items:get(i)
        local dmg = try(s, "getMaxDamage", 0)
        local cat = tostring(try(s, "getDisplayCategory", ""))
        if dmg > 0 and (try(s, "isRanged", false) or cat:find("Weapon")) then
            if try(s, "isRanged", false) then
                gun.dmg = math.max(gun.dmg, dmg)
                gun.range = math.max(gun.range, try(s, "getMaxRange", 0))
                gun.noise = math.max(gun.noise, try(s, "getSoundRadius", 0))
            else
                melee.dmg = math.max(melee.dmg, dmg)
                melee.range = math.max(melee.range, try(s, "getMaxRange", 0))
                melee.crowd = math.max(melee.crowd, try(s, "getMaxHitCount", 0))
                melee.knock = math.max(melee.knock, try(s, "getPushBackMod", 0))
                melee.cond = math.max(melee.cond, try(s, "getConditionMax", 0))
                melee.endure = math.max(melee.endure, try(s, "getConditionLowerChance", 0))
            end
        end
    end
    for _, t in ipairs({ gun, melee }) do
        for k, v in pairs(t) do t[k] = round(v, 2) end
    end
    maxima = { gun = gun, melee = melee }
    return maxima
end

-- ----------------------------------------------------------------------------- what the character has

local function isThrowable(w)
    return try(w, "getExplosionPower", 0) > 0 or try(w, "getFirePower", 0) > 0 or try(w, "getSmokeRange", 0) > 0
        or try(w, "getNoiseRange", 0) > 0 or try(w, "getTriggerExplosionTimer", 0) > 0
end

local function condOf(item)
    local m = try(item, "getConditionMax", 0)
    return m > 0 and round(item:getCondition() / m, 2) or nil
end

local function partRow(p)
    return { id = p:getID(), n = p:getDisplayName(), t = p:getFullType(), ic = KL.iconOf(p), k = try(p, "getPartType", ""), mods = modsOf(p) }
end

--- Rounds for a gun: in it, the most it holds, its magazine, and what the character carries for it.
local function ammoOf(player, w, all)
    local a = {
        n = try(w, "getCurrentAmmoCount", 0), max = try(w, "getMaxAmmo", 0),
        chambered = try(w, "isRoundChambered", false) or nil,
    }
    local magType = try(w, "getMagazineType", nil)
    local ammoType = try(w, "getAmmoType", nil)
    local ammoFull = ammoType and (try(ammoType, "getItemKey", nil) or tostring(ammoType)) or nil
    if magType then
        local s = getScriptManager():getItem(magType)
        a.mag = s and s:getDisplayName() or magType
        a.magIn = try(w, "isContainsClip", false) or nil
    end
    local spare, mags = 0, 0
    for _, it in ipairs(all) do
        local t = it:getFullType()
        if ammoFull and t == ammoFull then spare = spare + math.max(1, try(it, "getCount", 1)) end
        if magType and t == magType then mags = mags + 1 end
    end
    a.spare, a.mags = spare, mags
    if ammoFull then
        local s = getScriptManager():getItem(ammoFull)
        a.round = s and s:getDisplayName() or nil
    end
    return a
end

local function gunStats(w)
    -- the sight range: the optic's when there is one (the game's activeSight), else the gun's own (sight0)
    local base = round(try(w, "getMaxSightRange", 0), 1)
    local optic = try(w, "getActiveSight", nil)
    return {
        dmg = round((try(w, "getMinDamage", 0) + try(w, "getMaxDamage", 0)) / 2, 2),
        range = round(try(w, "getMaxRange", 0), 1), hit = try(w, "getHitChance", 0), aim = try(w, "getAimingTime", 0),
        recoil = try(w, "getRecoilDelay", 0), reload = try(w, "getReloadTime", 0), noise = try(w, "getSoundRadius", 0),
        sight = optic and round(try(optic, "getMaxSightRange", 0), 1) or base, sight0 = base,
        crit = round(try(w, "getCriticalChance", 0), 0),
        weight = round(try(w, "getActualWeight", 0), 2), pellets = try(w, "getProjectileCount", 1),
        mode = try(w, "getFireMode", nil),
    }
end

local function meleeStats(w)
    return {
        dmg = round((try(w, "getMinDamage", 0) + try(w, "getMaxDamage", 0)) / 2, 2),
        range = round(try(w, "getMaxRange", 0), 2), speed = round(try(w, "getBaseSpeed", 1), 2),
        crit = round(try(w, "getCriticalChance", 0), 0), knock = round(try(w, "getPushBackMod", 0), 2),
        crowd = try(w, "getMaxHitCount", 1), endure = try(w, "getConditionLowerChance", 0),
        condMax = try(w, "getConditionMax", 0), weight = round(try(w, "getActualWeight", 0), 2),
        two = try(w, "isTwoHandWeapon", false) or nil,
    }
end

--- Where a weapon is kept, when not in a bag: in a hand, or in a hotbar place (back, belt, holster).
local function places(player)
    local out = {}
    local hotbar = getPlayerHotbar and getPlayerHotbar(player:getPlayerNum())
    if hotbar and hotbar.attachedItems then
        for i, item in pairs(hotbar.attachedItems) do
            local slot = hotbar.availableSlot and hotbar.availableSlot[i]
            local name = slot and slot.name or nil
            if item then out[item] = name and getTextOrNull("IGUI_HotbarAttachment_" .. tostring(name)) or name or "Hotbar" end
        end
    end
    return out
end

local lastSig, lastAt = nil, 0

KL.addCollector("weapons", 2000, function(player)
    local all = KL.allItems(player)
    local p, s = player:getPrimaryHandItem(), player:getSecondaryHandItem()
    local where = places(player)
    local guns, melee, throw, carriedParts = {}, {}, {}, {}
    local partsHave = {}   -- part type -> {ids...}
    local sig = ""
    local throwBy = {}
    for _, it in ipairs(all) do
        if instanceof(it, "WeaponPart") then
            local t = it:getFullType()
            partsHave[t] = partsHave[t] or {}
            table.insert(partsHave[t], it)
        end
    end
    local carriedTypes = {}
    for _, it in ipairs(all) do
        if instanceof(it, "HandWeapon") and try(it, "getMaxDamage", 0) >= 0 then
            local t = it:getFullType()
            carriedTypes[t] = true
            local row = { id = it:getID(), n = it:getDisplayName(), t = t, ic = KL.iconOf(it), cond = condOf(it),
                broken = try(it, "isBroken", false) or nil, eq = (it == p and "p") or (it == s and "s") or nil, slot = where[it] }
            if isThrowable(it) then
                local tb = throwBy[t]
                if tb then
                    tb.cnt = tb.cnt + 1
                else
                    tb = { id = row.id, n = row.n, t = t, ic = row.ic, cnt = 1,
                        fire = try(it, "getFireRange", 0), blast = try(it, "getExplosionRange", 0), smoke = try(it, "getSmokeRange", 0),
                        noise = try(it, "getNoiseRange", 0), power = math.max(try(it, "getExplosionPower", 0), try(it, "getFirePower", 0)),
                        timer = try(it, "getTriggerExplosionTimer", 0) }
                    tb.kind = (tb.power > 0 or tb.fire > 0 or tb.blast > 0) and "lethal" or "tactical"
                    throwBy[t] = tb
                    table.insert(throw, tb)
                end
            elseif try(it, "isRanged", false) then
                row.stats = gunStats(it)
                row.ammo = ammoOf(player, it, all)
                row.slots = {}
                for _, k in ipairs(SLOTS) do
                    local fits = {}
                    for _, def in ipairs(allParts()) do
                        if def.k == k and def.mount[t] then
                            local have = partsHave[def.t]
                            table.insert(fits, { t = def.t, n = def.n, ic = def.ic, k = k, mods = def.mods,
                                have = have and #have or 0, id = have and have[1]:getID() or nil })
                        end
                    end
                    local on = try(it, "getWeaponPart", nil, k)
                    if #fits > 0 or on then
                        table.insert(row.slots, { k = k, part = on and partRow(on) or nil, fits = fits })
                        sig = sig .. k .. (on and on:getFullType() or "-")
                    end
                end
                table.insert(guns, row)
                sig = sig .. row.id .. ":" .. tostring(row.ammo.n) .. tostring(row.ammo.magIn) .. ";"
            else
                row.stats = meleeStats(it)
                if try(it, "hasSharpness", false) then
                    row.sharp = round(try(it, "getSharpness", 0), 2)
                    row.sharpMax = round(try(it, "getMaxSharpness", 1), 2)
                end
                if try(it, "hasHeadCondition", false) then
                    row.head = try(it, "getHeadCondition", 0)
                    row.headMax = try(it, "getHeadConditionMax", 0)
                end
                table.insert(melee, row)
            end
            sig = sig .. row.id .. ":" .. tostring(row.cond) .. tostring(row.eq) .. tostring(row.slot) .. tostring(row.sharp) .. ";"
        end
    end
    for t, list in pairs(partsHave) do sig = sig .. t .. #list .. ";" end
    -- parts and weapons seen elsewhere (the memory of "Where did I see...?")
    local wanted = {}
    for _, g in ipairs(guns) do
        for _, sl in ipairs(g.slots) do
            for _, f in ipairs(sl.fits) do
                if f.have == 0 then wanted[f.t] = true end
            end
        end
    end
    local seenParts = {}
    if KnoxLink_memoryNearest then
        local list = {}
        for t, _ in pairs(wanted) do table.insert(list, t) end
        if #list > 0 then
            local ok, at = pcall(KnoxLink_memoryNearest, list)
            if ok and at then seenParts = at end
        end
    end
    for _, g in ipairs(guns) do
        for _, sl in ipairs(g.slots) do
            for _, f in ipairs(sl.fits) do
                if f.have == 0 then f.at = seenParts[f.t] end
            end
        end
    end
    local seen = {}
    if KnoxLink_memoryKinds then
        local ok, list = pcall(KnoxLink_memoryKinds, "weapon", 30)
        if ok and list then
            for i = 1, #list do
                local e = list[i]
                if e and not carriedTypes[e.t] and not tostring(e.cat or ""):find("Part") then table.insert(seen, e) end
                if #seen >= 12 then break end
            end
        end
    end
    -- the strongest first, and what's in the hands before everything else
    local function rank(a, b)
        if (a.eq ~= nil) ~= (b.eq ~= nil) then return a.eq ~= nil end
        if (a.slot ~= nil) ~= (b.slot ~= nil) then return a.slot ~= nil end
        return (a.stats and a.stats.dmg or 0) > (b.stats and b.stats.dmg or 0)
    end
    table.sort(guns, rank)
    table.sort(melee, rank)
    table.sort(throw, function(a, b) return a.kind < b.kind end)
    local screwdriver = false
    if ItemTag and ItemTag.SCREWDRIVER then
        local ok, r = pcall(function()
            return player:getInventory():getFirstTagEvalRecurse(ItemTag.SCREWDRIVER, function(i) return not i:isBroken() end) ~= nil
        end)
        screwdriver = ok and r == true
    end
    sig = sig .. tostring(screwdriver) .. #seen
    local now = getTimestampMs()
    if sig == lastSig and now - lastAt < 6000 then return nil end
    lastSig, lastAt = sig, now
    return { guns = guns, melee = melee, throw = throw, seen = seen, screwdriver = screwdriver, max = allMaxima() }
end)

-- ----------------------------------------------------------------------------- the gunsmith

--- The game's own actions put the screwdriver (and the part) in the hands: a gun that was in them goes back after.
local function backInHands(player, w, wasInHand)
    if wasInHand and ISEquipWeaponAction then
        ISTimedActionQueue.add(ISEquipWeaponAction:new(player, w, 50, true, w:isTwoHandWeapon()))
    end
end

KL.addHandler("weaponAttach", function(player, a)
    local w, part = KL.findItem(player, a.w), KL.findItem(player, a.p)
    if not (w and instanceof(w, "HandWeapon")) then return false, "That gun is no longer on you" end
    if not (part and instanceof(part, "WeaponPart")) then return false, "That part is no longer on you" end
    if not part:canAttach(player, w) then return false, "You need a screwdriver" end
    local inHand = player:getPrimaryHandItem() == w
    local on = w:getWeaponPart(part:getPartType())
    if on then
        if not on:canDetach(player, w) then return false, "You need a screwdriver" end
        ISInventoryPaneContextMenu.onRemoveUpgradeWeapon(w, on, player)   -- the old one off first
    end
    ISInventoryPaneContextMenu.onUpgradeWeapon(w, part, player)
    backInHands(player, w, inHand)
    KL.refresh("weapons")
    return true, part:getDisplayName()
end)

KL.addHandler("weaponDetach", function(player, a)
    local w = KL.findItem(player, a.w)
    if not (w and instanceof(w, "HandWeapon")) then return false, "That gun is no longer on you" end
    local on = w:getWeaponPart(tostring(a.k or ""))
    if not on then return false, "Nothing there" end
    if not on:canDetach(player, w) then return false, "You need a screwdriver" end
    local inHand = player:getPrimaryHandItem() == w
    ISInventoryPaneContextMenu.onRemoveUpgradeWeapon(w, on, player)
    backInHands(player, w, inHand)
    KL.refresh("weapons")
    return true, on:getDisplayName()
end)
