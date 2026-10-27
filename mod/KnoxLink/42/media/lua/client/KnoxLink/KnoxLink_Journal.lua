--[[
    Knox Link — the survivor's journal.

    It writes itself as you play: each day gets a page with what happened to you (zombies
    killed, skills learned, wounds, sickness, places found, sleep, a base set up), and you can
    add your own notes. Only things the character knows go in. Kept in the save, a few lines a day.

    Each page also gets a diary entry in the character's own voice. The offline writer
    (KnoxLink_Diary) writes it here, from the page, how the character felt that day, the weather,
    the town and who they are; a screen with the AI writer turned on can send its own entry for
    a page ("journalEntry"), which is kept next to the offline one.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"
require "KnoxLink/KnoxLink_Diary"

local KL = KnoxLink
local try = KL.try

local MAX_DAYS, MAX_LINES, MAX_NOTES = 60, 24, 20
local MAX_ENTRY, MAX_AI = 900, 1500
-- today's AI entry is asked for again at most this many in-game hours apart (each one costs the user)
local AI_EVERY_HOURS = 4
local version, sent, sentDue = 0, -1, ""

-- ----------------------------------------------------------------------------- who the character is

--- Build 42.13+ checks traits through the CharacterTrait registry, Build 41 and early 42 by name.
local function has(player, key, legacy)
    if CharacterTrait then
        local okT, t = pcall(function() return CharacterTrait[key] end)
        if not (okT and t) then return false end
        local ok, r = pcall(function() return player:hasTrait(t) end)
        return ok and r == true
    end
    local ok, r = pcall(function() return player:HasTrait(legacy) end)
    return ok and r == true
end

local function illiterate(player)
    return has(player, "ILLITERATE", "Illiterate")
end

--- The profession's id ("policeofficer", as the game's scripts name it) and its name on screen.
local function profession(player)
    local desc = player:getDescriptor()
    local id, label
    local prof = try(desc, "getCharacterProfession", nil)
    if prof then
        id = try(prof, "getName", nil)
        if CharacterProfessionDefinition then
            local ok, def = pcall(function() return CharacterProfessionDefinition.getCharacterProfessionDefinition(prof) end)
            if ok and def then label = try(def, "getUIName", nil) end
        end
    else
        id = try(desc, "getProfession", nil)   -- Build 41 and early 42: the id itself
        if type(id) == "string" and ProfessionFactory then
            local ok, def = pcall(function() return ProfessionFactory.getProfession(id) end)
            if ok and def then label = try(def, "getName", nil) end
        end
    end
    if type(id) ~= "string" or id == "" then return nil, label end
    return string.lower((string.gsub(id, "^.*:", ""))), label
end

--- The traits the character screen shows (with an icon), by their names on screen.
local function traitLabels(player)
    local out = {}
    local ct = try(player, "getCharacterTraits", nil)
    local known = ct and try(ct, "getKnownTraits", nil)
    if known then
        if not CharacterTraitDefinition then return out end
        for i = 0, known:size() - 1 do
            local t = known:get(i)
            if t then
                local ok, def = pcall(function() return CharacterTraitDefinition.getCharacterTraitDefinition(t) end)
                if ok and def and try(def, "getTexture", nil) then
                    local label = try(def, "getLabel", nil)
                    if label then table.insert(out, label) end
                end
            end
        end
        return out
    end
    local list = try(player, "getTraits", nil)   -- Build 41: trait ids
    if list and TraitFactory then
        for i = 0, list:size() - 1 do
            local id = list:get(i)
            local ok, tr = pcall(function() return TraitFactory.getTrait(id) end)
            local label = ok and tr and try(tr, "getLabel", nil)
            if label then table.insert(out, label) end
        end
    end
    return out
end

-- trait -> a word the diary writer looks for
local TRAIT_TAGS = {
    { "SMOKER", "Smoker", "smoker" }, { "FAST_READER", "FastReader", "bookish" },
    { "HEARTY_APPETITE", "HeartyAppetite", "hearty" }, { "PRONE_TO_ILLNESS", "ProneToIllness", "prone" },
    { "INSOMNIAC", "Insomniac", "insomniac" }, { "PACIFIST", "Pacifist", "pacifist" },
    { "HEMOPHOBIC", "Hemophobic", "hemo" }, { "SPEED_DEMON", "SpeedDemon", "speed" },
    { "SUNDAY_DRIVER", "SundayDriver", "sunday" }, { "OUTDOORSMAN", "Outdoorsman", "outdoors" },
}
local JOB_TAGS = {
    doctor = { "medic", "bookish" }, nurse = { "medic" }, engineer = { "bookish" }, chef = { "cook" },
    burgerflipper = { "cook" }, fitnessinstructor = { "fitness" }, unemployed = { "unemployed" },
    mechanics = { "mech" }, parkranger = { "outdoors" }, lumberjack = { "outdoors" }, farmer = { "outdoors" },
    fisherman = { "outdoors" }, rancher = { "outdoors" },
}

local function character(player)
    local desc = player:getDescriptor()
    local first, last = tostring(desc:getForename() or ""), tostring(desc:getSurname() or "")
    local job, jobLabel = profession(player)
    local tone = nil
    if has(player, "DESENSITIZED", "Desensitized") then tone = "hard"
    elseif has(player, "BRAVE", "Brave") then tone = "brave"
    elseif has(player, "COWARDLY", "Cowardly") then tone = "scared" end
    local tags = {}
    if job then table.insert(tags, job) end
    for _, t in ipairs(JOB_TAGS[job or ""] or {}) do table.insert(tags, t) end
    for _, t in ipairs(TRAIT_TAGS) do
        if has(player, t[1], t[2]) then table.insert(tags, t[3]) end
    end
    local full = (string.gsub(string.gsub(first .. " " .. last, "^%s+", ""), "%s+$", ""))
    return { first = first, full = full, job = job, jobLabel = jobLabel, tone = tone, tags = tags }
end

-- ----------------------------------------------------------------------------- the pages

local MDAYS = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 }

local function daysBefore(y, mo, d, n)
    for _ = 1, n do
        d = d - 1
        if d < 1 then
            mo = mo - 1
            if mo < 1 then mo, y = 12, y - 1 end
            d = (mo == 2 and y % 4 == 0 and (y % 100 ~= 0 or y % 400 == 0)) and 29 or MDAYS[mo]
        end
    end
    return y, mo, d
end

--- The calendar day the character's day 1 began (for the AI writer's dates).
local function startDate(player)
    local gt = getGameTime()
    local hs = player:getHoursSurvived()
    local now = gt:getHour() + gt:getMinutes() / 60
    local back = hs > now and math.ceil((hs - now) / 24) or 0
    local y, mo, d = daysBefore(gt:getYear(), gt:getMonth() + 1, gt:getDay() + 1, back)
    return { y = y, mo = mo, d = d }
end

local function journal(player)
    local md = player:getModData()
    if type(md.KnoxLinkJournal) ~= "table" then md.KnoxLinkJournal = { days = {}, seen = {} } end
    local j = md.KnoxLinkJournal
    j.days = j.days or {}
    j.seen = j.seen or {}
    -- tells this character's journal from another's on the screens (their AI story, cached pages)
    if not j.id then j.id = tostring(ZombRand(100000)) .. "-" .. tostring(ZombRand(100000)) end
    if not j.start then j.start = startDate(player) end
    return j
end

--- Days survived, as the game counts them.
local function dayNumber(player)
    return math.floor(player:getHoursSurvived() / 24) + 1
end

local function hasFacts(p)
    return (p.kills or 0) > 0 or #(p.lines or {}) > 0 or #(p.notes or {}) > 0
end

--- Something new on a page: its diary entries are due for a rewrite.
local function bump(p)
    p.rev = (p.rev or 0) + 1
    version = version + 1
end

--- (Re)writes a page's offline diary entry. final: the day is over, this is its last word.
local function writeEntry(player, j, p, final)
    if final then p.fin = true end
    p.erev, p.eh = p.rev or 0, player:getHoursSurvived()
    if illiterate(player) then return end
    local who = character(player)
    -- kills on this page and the ones after it: the rest of the character's kills came before
    local bitten, later = false, 0
    for _, q in ipairs(j.days) do
        if q.d < p.d then
            for _, l in ipairs(q.lines or {}) do
                if l.k == "bite" then bitten = true end
            end
        else
            later = later + (q.kills or 0)
        end
    end
    local ok, text = pcall(KL.diaryEntry, {
        seed = who.full .. "|" .. tostring(p.d), day = p.d, final = p.fin == true, hour = getGameTime():getHour(),
        first = who.first, full = who.full, job = who.job, jobLabel = who.jobLabel, tone = who.tone, tags = who.tags,
        page = p, bittenBefore = bitten, killsBefore = try(player, "getZombieKills", 0) - later > 0,
    })
    if not ok then
        KL.warn("diary", text)
        return
    end
    if type(text) == "string" and text ~= "" then
        if #text > MAX_ENTRY then text = KL.diaryClip(text, MAX_ENTRY) end
        if text ~= p.entry then
            p.entry = text
            version = version + 1
        end
    end
end

local function page(player)
    local j = journal(player)
    local n = dayNumber(player)
    local last = j.days[#j.days]
    if not last or last.d ~= n then
        -- the day is over: its entry gets its last word, with everything that happened
        if last and not last.fin then writeEntry(player, j, last, true) end
        last = { d = n, kills = 0, lines = {}, notes = {}, rev = 0 }
        table.insert(j.days, last)
        while #j.days > MAX_DAYS do
            -- the oldest page goes, and its photos with it
            local old = table.remove(j.days, 1)
            for _, ph in ipairs(old.ph or {}) do
                if KnoxLink_photoDelete and ph.id then KnoxLink_photoDelete(ph.id) end
            end
        end
        version = version + 1
    end
    last.lines = last.lines or {}
    last.notes = last.notes or {}
    -- pages from before the diary writer: count what's on them as their first revision
    if not last.rev then last.rev = #last.lines + #last.notes + ((last.kills or 0) > 0 and 1 or 0) end
    return last
end

--- Today's entry follows what happens, at most once an in-game hour; a quiet day gets one after a while.
local function maybeWrite(player, j, p)
    if p.fin then return end
    local rev = p.rev or 0
    if p.erev == rev then return end   -- already written (or tried, for a character who can't write) for this
    local hs = player:getHoursSurvived()
    if p.entry then
        if hs - (p.eh or 0) < 1 then return end
    elseif rev == 0 and hs - (p.d - 1) * 24 < 3 then
        return
    end
    writeEntry(player, j, p, false)
end

--- A page the AI writer (on a screen) should write: it has something to tell and no AI entry for
--- what it says now. Today's page at most every few in-game hours; a past day once more at most.
local function aiDue(p, today, hs)
    if not hasFacts(p) then return false end
    if not p.ai then return true end
    if p.arev == (p.rev or 0) then return false end
    if p.d == today then return hs - (p.ah or 0) >= AI_EVERY_HOURS end
    return true
end

local function stamp(player)
    local gt = getGameTime()
    return gt:getHour(), gt:getMinutes(), (KL.clockOf(player) or not KL.immersive()) and true or nil
end

--- Adds a line to today's page. kind: "skill", "hurt", "bite", "sick", "place", "sleep", "base", "car".
--- about: an id for the diary writer (the skill's id), optional.
function KL.journalAdd(player, kind, text, about)
    if not player or player:isDead() then return end
    local p = page(player)
    local last = p.lines[#p.lines]
    if last and last.s == text then return end   -- the same thing twice in a row is noise
    local h, mi, w = stamp(player)
    table.insert(p.lines, { k = kind, s = text, h = h, mi = mi, w = w, a = about })
    while #p.lines > MAX_LINES do table.remove(p.lines, 1) end
    bump(p)
end

-- ----------------------------------------------------------------------------- what happens

local PART_WORDS = {
    Hand_L = "left hand", Hand_R = "right hand", ForeArm_L = "left forearm", ForeArm_R = "right forearm",
    UpperArm_L = "left arm", UpperArm_R = "right arm", Torso_Upper = "chest", Torso_Lower = "belly",
    Head = "head", Neck = "neck", Groin = "groin", UpperLeg_L = "left thigh", UpperLeg_R = "right thigh",
    LowerLeg_L = "left shin", LowerLeg_R = "right shin", Foot_L = "left foot", Foot_R = "right foot",
}

-- room names (the game's own) worth a line the first time you walk into one
local PLACES = {
    pharmacy = "a pharmacy", pharmacystorage = "a pharmacy", gunstore = "a gun store", gunstorestorage = "a gun store",
    policestorage = "a police station", policehall = "a police station", grocery = "a grocery store",
    grocerystorage = "a grocery store", hospitalroom = "a hospital", medical = "a doctor's office",
    warehouse = "a warehouse", firestorage = "a fire station", mechanic = "a mechanic's garage",
    toolstore = "a hardware store", hardwarestore = "a hardware store", gasstore = "a gas station",
    gasstation = "a gas station", library = "a library", church = "a church", school = "a school",
    classroom = "a school", bakery = "a bakery", liquorstore = "a liquor store", spiffoskitchen = "Spiffo's",
    farmstorage = "a farm", motelroom = "a motel", camping = "a camping store",
}

-- the towns on the game's world map (its labels in media/maps/Muldraugh, KY/worldmap-annotations.lua):
-- translation key, English name, label position, how far out it still counts as that town
local TOWNS = {
    { "MapLabel_Muldraugh", "Muldraugh", 10754, 9926, 1000 }, { "MapLabel_WestPoint", "West Point", 11654, 6864, 1000 },
    { "MapLabel_Riverside", "Riverside", 6450, 5430, 900 }, { "MapLabel_Rosewood", "Rosewood", 8159, 11661, 900 },
    { "MapLabel_MarchRidge", "March Ridge", 10130, 12801, 800 }, { "MapLabel_Louisville", "Louisville", 13077, 2238, 2100 },
    { "MapLabel_ValleyStation", "Valley Station", 13447, 5278, 900 }, { "MapLabel_Brandenburg", "Brandenburg", 2056, 6070, 1000 },
    { "MapLabel_Ekron", "Ekron", 634, 9746, 900 }, { "MapLabel_Irvington", "Irvington", 2427, 14185, 1000 },
    { "MapLabel_EchoCreek", "Echo Creek", 3589, 10952, 900 }, { "MapLabel_FallasLake", "Fallas Lake", 7253, 8279, 900 },
}

local function townName(t)
    local s = getTextOrNull and getTextOrNull(t[1]) or nil
    if type(s) ~= "string" or s == "" then return t[2] end
    -- the map writes its labels in capitals (Kahlua hands a gsub function only the first capture)
    local lower = string.gsub(string.lower(s), "^%l", string.upper)
    return (string.gsub(lower, "[%s%-]%l", string.upper))
end

local function townAt(x, y)
    local best, bestD = nil, nil
    for _, t in ipairs(TOWNS) do
        local dx, dy = x - t[3], y - t[4]
        local d = math.sqrt(dx * dx + dy * dy)
        if d <= t[5] and (not bestD or d < bestD) then best, bestD = t, d end
    end
    return best and townName(best) or nil
end

local MOOD_KEYS = { "PANIC", "STRESS", "UNHAPPY", "BORED", "PAIN", "SICK", "TIRED", "HUNGRY", "THIRST", "DRUNK",
    "HAS_A_COLD", "HYPOTHERMIA", "HYPERTHERMIA", "WET", "ENDURANCE", "ANGRY" }
-- Build 42.13+ names moodles in capitals, Build 41 and early 42 in CamelCase
local MOOD_CAMEL = { PANIC = "Panic", STRESS = "Stress", UNHAPPY = "Unhappy", BORED = "Bored", PAIN = "Pain",
    SICK = "Sick", TIRED = "Tired", HUNGRY = "Hungry", THIRST = "Thirst", DRUNK = "Drunk", HAS_A_COLD = "HasACold",
    HYPOTHERMIA = "Hypothermia", HYPERTHERMIA = "Hyperthermia", WET = "Wet", ENDURANCE = "Endurance", ANGRY = "Angry" }
local moodTypes = nil

local function moodLevel(moodles, key)
    if not moodTypes then
        moodTypes = {}
        for _, k in ipairs(MOOD_KEYS) do
            local ok, t = pcall(function() return MoodleType[k] end)
            if not (ok and t) then ok, t = pcall(function() return MoodleType[MOOD_CAMEL[k]] end) end
            if ok and t then moodTypes[k] = t end
        end
    end
    local mt = moodTypes[key]
    if not mt then return 0 end
    return try(moodles, "getMoodleLevel", 0, mt)
end

-- the strongest each feeling got today (what the entry says about how the day felt)
local function samplePeaks(player, p)
    local moodles = player:getMoodles()
    if not moodles then return end
    for _, k in ipairs(MOOD_KEYS) do
        local lvl = moodLevel(moodles, k)
        if lvl >= 2 then
            p.peak = p.peak or {}
            if lvl > (p.peak[k] or 0) then p.peak[k] = lvl end
        end
    end
end

-- what the weather did today, as the character felt it (heard rain counts indoors too)
local function sampleWeather(player, p)
    local cm = getClimateManager()
    if not cm then return end
    local outside = try(player, "isOutside", false)
    local function felt(what)
        p.wx = p.wx or {}
        p.wx[what] = true
    end
    if try(cm, "getIsThunderStorming", false) then felt("storm") end
    if try(cm, "getSnowIntensity", 0) > 0.2 then felt("snow")
    elseif try(cm, "getRainIntensity", 0) > 0.3 then felt("rain") end
    if outside and try(cm, "getFogIntensity", 0) > 0.5 then felt("fog") end
    if outside and try(cm, "getWindspeedKph", 0) >= 45 then felt("wind") end
    local temp = try(cm, "getTemperature", 15)
    if temp >= 32 then felt("hot") elseif temp <= 1 then felt("cold") end
end

-- the towns the character was in today, in order, and whether they spent time in the woods
local function samplePlace(player, p)
    local name = townAt(player:getX(), player:getY())
    if name then
        p.at = p.at or {}
        local seen = false
        for _, t in ipairs(p.at) do seen = seen or t == name end
        if not seen and #p.at < 4 then
            table.insert(p.at, name)
            if #p.at > 1 then bump(p) end   -- travelling to another town is news
        end
    end
    local sq = player:getCurrentSquare()
    local zone = sq and try(sq, "getZone", nil)
    local ztype = zone and try(zone, "getType", nil)
    if type(ztype) == "string" and string.find(ztype, "Forest", 1, true) then p.woods = true end
end

local wounds = {}          -- body part -> flags last minute (this session)
local lastKills, lastSick, lastCarId = nil, false, nil
local sleptAt = nil

local function checkBody(player)
    local parts = player:getBodyDamage():getBodyParts()
    for i = 0, parts:size() - 1 do
        local bp = parts:get(i)
        local key = tostring(bp:getType())
        local now = {
            bite = try(bp, "bitten", false), deep = try(bp, "deepWounded", false), cut = try(bp, "isCut", false),
            scratch = try(bp, "scratched", false), burn = try(bp, "getBurnTime", 0) > 0,
            fracture = try(bp, "getFractureTime", 0) > 0, glass = try(bp, "haveGlass", false),
        }
        local before = wounds[key]
        if before then
            local where = PART_WORDS[key] or key
            if now.bite and not before.bite then KL.journalAdd(player, "bite", "Bitten on the " .. where .. ".")
            elseif now.deep and not before.deep then KL.journalAdd(player, "hurt", "A deep wound on the " .. where .. ".")
            elseif now.fracture and not before.fracture then KL.journalAdd(player, "hurt", "Broke my " .. where .. ".")
            elseif now.cut and not before.cut then KL.journalAdd(player, "hurt", "Cut my " .. where .. ".")
            elseif now.burn and not before.burn then KL.journalAdd(player, "hurt", "Burned my " .. where .. ".")
            elseif now.glass and not before.glass then KL.journalAdd(player, "hurt", "Glass in my " .. where .. ".")
            elseif now.scratch and not before.scratch then KL.journalAdd(player, "hurt", "Scratched on the " .. where .. ".")
            end
        end
        wounds[key] = now
    end
end

local function checkPlace(player)
    local sq = player:getCurrentSquare()
    local room = sq and sq:getRoom()
    local name = room and try(room, "getName", nil)
    local place = name and PLACES[name]
    if not place then return end
    local building = try(room, "getBuilding", nil)
    local def = building and try(building, "getDef", nil)
    local key = def and (tostring(try(def, "getX", 0)) .. "," .. tostring(try(def, "getY", 0))) or name
    local j = journal(player)
    if j.seen[key] then return end
    j.seen[key] = true
    KL.journalAdd(player, "place", "Found " .. place .. ".")
end

Events.EveryOneMinute.Add(function()
    local player = getPlayer()
    if not player or player:isDead() then return end
    local ok, err = pcall(function()
        local p = page(player)
        local kills = player:getZombieKills()
        if lastKills and kills > lastKills then
            p.kills = (p.kills or 0) + (kills - lastKills)
            bump(p)
        end
        lastKills = kills
        checkBody(player)
        checkPlace(player)
        local moodles = player:getMoodles()
        local sick = moodles ~= nil and moodLevel(moodles, "SICK") >= 2
        if sick and not lastSick then KL.journalAdd(player, "sick", "Feeling sick.") end
        lastSick = sick
        local v = player:getVehicle()
        if v and v:isDriver(player) then
            local id = try(v, "getId", nil) or try(v, "getSqlId", nil)
            if id ~= lastCarId then
                lastCarId = id
                local script = try(v, "getScript", nil)
                local sname = script and try(script, "getName", nil)
                local name = sname and (getTextOrNull("IGUI_VehicleName" .. sname) or sname) or "a car"
                KL.journalAdd(player, "car", "Drove the " .. name .. ".")
            end
        end
        local asleep = try(player, "isAsleep", false)
        if asleep and not sleptAt then
            sleptAt = getGameTime():getWorldAgeHours()
        elseif not asleep and sleptAt then
            local hours = getGameTime():getWorldAgeHours() - sleptAt
            sleptAt = nil
            if hours >= 1 then KL.journalAdd(player, "sleep", "Slept " .. math.floor(hours + 0.5) .. " hours.") end
        end
        p = page(player)
        samplePeaks(player, p)
        maybeWrite(player, journal(player), p)
    end)
    if not ok then KL.warn("journal", err) end
end)

Events.EveryTenMinutes.Add(function()
    local player = getPlayer()
    if not player or player:isDead() then return end
    local ok, err = pcall(function()
        local p = page(player)
        sampleWeather(player, p)
        samplePlace(player, p)
    end)
    if not ok then KL.warn("journal", err) end
end)

local readyAt = nil

Events.LevelPerk.Add(function(character, perk, level, increased)
    if not increased or character ~= getPlayer() then return end
    -- a new character gets its starting levels as a burst of level-ups: those aren't news
    if not readyAt or getTimestampMs() < readyAt or character:getHoursSurvived() < 0.1 then return end
    local name = nil
    pcall(function() name = PerkFactory.getPerkName(perk) end)
    if not name then pcall(function() name = perk:getName() end) end
    local id = try(perk, "getId", nil)
    KL.journalAdd(character, "skill", tostring(name or perk) .. " is now level " .. tostring(level) .. ".",
        type(id) == "string" and id or nil)
end)

Events.OnGameStart.Add(function()
    readyAt = getTimestampMs() + 5000
    local player = getPlayer()
    if player then
        lastKills = player:getZombieKills()
        local ok, err = pcall(function()
            local j = journal(player)
            page(player)
            -- pages from before the diary writer (or a day that ended while the game was closed)
            for i = 1, #j.days - 1 do
                if not j.days[i].fin then writeEntry(player, j, j.days[i], true) end
            end
        end)
        if not ok then KL.warn("journal", err) end
    end
    version = version + 1
end)

-- a new character in the same world (after a death) starts with a clean page and no burst
Events.OnCreatePlayer.Add(function(playerNum, player)
    readyAt = getTimestampMs() + 5000
    if player and playerNum == 0 then lastKills = player:getZombieKills() end
end)

-- ----------------------------------------------------------------------------- for the other parts

--- This character's id (it tells their memory, photos and diary from another character's).
function KL.journalId(player)
    return journal(player).id
end

--- Who the character is ({first, full, job, jobLabel, tone, tags}) and their traits' names.
function KL.whoIs(player)
    return character(player), traitLabels(player)
end

--- The town on the world map around a place, or nil.
KL.townAt = townAt

--- The pages, and who wrote them, for the diary left behind (KnoxLink_Legacy); the last
--- page's entry gets its last word first.
function KL.journalOf(player)
    local j = journal(player)
    local last = j.days[#j.days]
    if last and not last.fin then
        local ok, err = pcall(writeEntry, player, j, last, true)
        if not ok then KL.warn("journal", err) end
    end
    return j, character(player), traitLabels(player)
end

local MAX_PHOTOS = 12   -- a day

--- Whether today's page has room for another photo.
function KL.journalPhotoRoom(player)
    local p = page(player)
    return #(p.ph or {}) < MAX_PHOTOS
end

--- A photo on today's page (the scrapbook): ph = { {id, c = caption, h, mi, w}, ... }.
function KL.journalPhoto(player, id, caption)
    local p = page(player)
    p.ph = p.ph or {}
    if #p.ph >= MAX_PHOTOS then return false end
    local h, mi, w = stamp(player)
    table.insert(p.ph, { id = id, c = caption, h = h, mi = mi, w = w })
    local line = caption and caption ~= "" and ("Took a photo: " .. caption .. ".") or "Took a photo."
    local last = p.lines[#p.lines]
    if not (last and last.k == "photo" and not caption) then
        table.insert(p.lines, { k = "photo", s = line, h = h, mi = mi, w = w })
        while #p.lines > MAX_LINES do table.remove(p.lines, 1) end
    end
    bump(p)
    return true
end

--- Changes a photo's caption, or (caption == false) takes it off its page. Returns whether it was there.
function KL.journalPhotoEdit(player, id, caption)
    for _, p in ipairs(journal(player).days) do
        for i, ph in ipairs(p.ph or {}) do
            if ph.id == id then
                if caption == false then table.remove(p.ph, i) else ph.c = caption end
                version = version + 1
                return true
            end
        end
    end
    return false
end

-- ----------------------------------------------------------------------------- screens

local function whoOf(player, j)
    local who = character(player)
    local world = getWorld()
    return {
        id = j.id,
        world = world and try(world, "getWorld", nil) or nil,
        name = who.full,
        first = who.first,
        female = try(player, "isFemale", false) or nil,
        prof = who.jobLabel,
        profId = who.job,
        traits = traitLabels(player),
        start = j.start,
    }
end

KL.addCollector("journal", 1500, function(player)
    local j = journal(player)
    local today = page(player)
    local write = not illiterate(player)
    local hs = player:getHoursSurvived()
    -- which pages the AI writer should write changes with the clock too, not only with the pages
    local due, dueSig = {}, ""
    if write then
        for _, p in ipairs(j.days) do
            if aiDue(p, today.d, hs) then
                due[p.d] = true
                dueSig = dueSig .. tostring(p.d) .. ","
            end
        end
    end
    if version == sent and dueSig == sentDue then return nil end
    sent, sentDue = version, dueSig
    local days = {}
    for i = #j.days, 1, -1 do   -- newest first
        local p = j.days[i]
        local o = {}
        for k, v in pairs(p) do o[k] = v end
        o.erev, o.eh, o.ah = nil, nil, nil   -- the writers' bookkeeping
        o.due = due[p.d]
        table.insert(days, o)
    end
    return { days = days, today = today.d, write = write, who = whoOf(player, j) }
end)

KL.addHandler("note", function(player, a)
    if illiterate(player) then return false, "Your character can't write" end
    local text = tostring(a.s or ""):gsub("[\r\n]", " ")
    if text:match("^%s*$") then return false, "Nothing to write" end
    if #text > 280 then text = text:sub(1, 280) end
    local p = page(player)
    local h, mi, w = stamp(player)
    table.insert(p.notes, { s = text, h = h, mi = mi, w = w })
    while #p.notes > MAX_NOTES do table.remove(p.notes, 1) end
    bump(p)
    return true
end)

KL.addHandler("noteDelete", function(player, a)
    local j = journal(player)
    for _, p in ipairs(j.days) do
        if p.d == tonumber(a.d) and p.notes and p.notes[tonumber(a.i) or 0] then
            table.remove(p.notes, tonumber(a.i))
            version = version + 1
            return true
        end
    end
    return false, "That note is gone"
end)

--- The AI writer's entry for a page: {d = day, s = text, r = the page's rev it was written for}.
--- An empty text takes it back out ("write this page again").
KL.addHandler("journalEntry", function(player, a)
    if illiterate(player) then return false, "Your character can't write" end
    local d = tonumber(a.d)
    for _, p in ipairs(journal(player).days) do
        if p.d == d then
            local s = string.gsub(tostring(a.s or ""), "\r", "")
            s = string.gsub(s, "\n%s*\n[%s]*", "\n\n")
            s = string.gsub(string.gsub(s, "^%s+", ""), "%s+$", "")
            if s == "" then
                p.ai, p.arev, p.ah = nil, nil, nil
            else
                if #s > MAX_AI then s = KL.diaryClip(s, MAX_AI) end
                p.ai, p.arev, p.ah = s, tonumber(a.r) or (p.rev or 0), player:getHoursSurvived()
            end
            version = version + 1
            return true
        end
    end
    return false, "That page is gone"
end)
