--[[
    Knox Link — the offline diary writer.

    Turns one journal page (what happened that day, how the character felt, the weather, where
    they were) into a short diary entry in the first person, in the character's own voice: their
    job, traits and nerve pick the words. Free, and it needs no connection.

    Every choice is seeded by the character's name and the day, so a page reads the same each
    time it is written, and a new fact adds a sentence instead of reshuffling the others.
    Only what the character knows goes in: no numbers they couldn't know, no events that didn't
    happen to them.
]]

require "KnoxLink/KnoxLink_Core"

local KL = KnoxLink

-- ----------------------------------------------------------------------------- choosing words

local function hash(s)
    local h = 5381
    for i = 1, #s do h = (h * 33 + string.byte(s, i)) % 2147483647 end
    return h
end

local function pick(ctx, slot, list)
    if not list or #list == 0 then return nil end
    return list[hash(ctx.seed .. "|" .. slot) % #list + 1]
end

local function chance(ctx, slot, percent)
    return hash(ctx.seed .. "?" .. slot) % 100 < percent
end

-- one of t.any, or (twice as likely) a line written for this character's nerve or one of their tags
local function voiced(ctx, slot, t)
    local list = {}
    local function twice(l)
        if not l then return end
        for _, s in ipairs(l) do
            list[#list + 1] = s
            list[#list + 1] = s
        end
    end
    if ctx.tone then twice(t[ctx.tone]) end
    for _, tag in ipairs(ctx.tags or {}) do twice(t[tag]) end
    for _, s in ipairs(t.any or {}) do list[#list + 1] = s end
    return pick(ctx, slot, list)
end

local function fill(s, vars)
    if not s then return nil end
    return (string.gsub(s, "{(%a+)}", function(k)
        local v = vars[k]
        if v == nil then return "" end
        return tostring(v)
    end))
end

local function upper1(s)
    return (string.gsub(s, "^%l", string.upper))
end

local WORDS = { "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve" }

local function count(n)
    return WORDS[n] or tostring(n)
end

-- a, b and c
local function join(items)
    local s = items[1] or ""
    for i = 2, #items do s = s .. (i == #items and " and " or ", ") .. items[i] end
    return s
end

local function hasTag(ctx, tag)
    for _, t in ipairs(ctx.tags or {}) do
        if t == tag then return true end
    end
    return false
end

local function sentences(s)
    local n = 0
    for _ in string.gmatch(s .. " ", "[%.!%?]['\"]?%s") do n = n + 1 end
    return math.max(n, 1)
end

--- Shortens text to at most max characters, at the end of a sentence where it can.
function KL.diaryClip(s, max)
    if #s <= max then return s end
    local cut = string.sub(s, 1, max)
    for i = #cut, math.floor(max / 2), -1 do
        local ch = string.sub(cut, i, i)
        if ch == "." or ch == "!" or ch == "?" then return string.sub(cut, 1, i) end
    end
    return (string.gsub(cut, "%s+%S*$", "")) .. "..."
end

-- ----------------------------------------------------------------------------- the words

local OPEN_FIRST = {
    "I don't know who I'm writing this for. Maybe just for me.",
    "If I don't write this down, I think I'll lose my mind.",
    "Everything fell apart so fast. I need to keep track of the days.",
    "Somebody should write down what's happening. Might as well be me.",
}

-- day one: who they were (the profession's id, as the game names it)
local BEFORE = {
    burglar = "Before all this, I got into other people's houses for a living. Now everybody does.",
    burgerflipper = "Before all this, my biggest worry was the lunch rush.",
    carpenter = "Before all this, I built things for a living.",
    chef = "Before all this, I ran a kitchen.",
    constructionworker = "Before all this, I worked construction.",
    doctor = "Before all this, I was a doctor. I suppose I still am.",
    electrician = "Before all this, I was an electrician.",
    engineer = "Before all this, I was an engineer. Problems had solutions back then.",
    farmer = "Before all this, I farmed. Up before dawn every day.",
    fireofficer = "Before all this, I was a firefighter.",
    fisherman = "Before all this, I fished for a living.",
    fitnessinstructor = "Before all this, I taught fitness classes.",
    lumberjack = "Before all this, I cut timber.",
    mechanics = "Before all this, I fixed cars.",
    metalworker = "Before all this, I worked metal.",
    nurse = "Before all this, I was a nurse.",
    parkranger = "Before all this, I was a park ranger.",
    policeofficer = "Before all this, I was a cop.",
    rancher = "Before all this, I worked a ranch.",
    repairman = "Before all this, I fixed whatever people broke.",
    securityguard = "Before all this, I was a security guard. Night shifts, mostly.",
    smither = "Before all this, I was a blacksmith. Yes, really.",
    tailor = "Before all this, I was a tailor.",
    unemployed = "Before all this, I was out of work and going nowhere.",
    veteran = "I did my time in the army. I thought I'd seen the worst of people.",
}

local MILESTONE = {
    [7] = "A week now.",
    [10] = "Ten days.",
    [14] = "Two weeks.",
    [21] = "Three weeks now.",
    [30] = "A whole month. I never thought I'd make it this far.",
    [50] = "Fifty days.",
    [60] = "Two months.",
    [100] = "A hundred days. A hundred.",
    [180] = "Half a year.",
    [365] = "A year. I'm still here.",
}

local OPEN = {
    any = { "Day {day}.", "Another day.", "Still here.", "Day {day}, if I'm counting right." },
    brave = { "Another day. Still standing." },
    scared = { "Still alive. I don't know how.", "Still alive." },
    hard = { "Day {day}.", "Another one." },
}

local WEATHER_ORDER = { "storm", "snow", "rain", "fog", "cold", "hot", "wind" }
local WEATHER = {
    storm = { "Thunderstorm today.", "Thunder and lightning half the day.", "A storm rolled through." },
    snow = { "Snow today.", "It snowed.", "Everything's white." },
    rain = { "Rain.", "It rained most of the day.", "Grey and wet all day." },
    fog = { "Fog everywhere.", "Thick fog today. Couldn't see far.", "Fog. I couldn't see them coming." },
    cold = { "Freezing today.", "Bitter cold.", "Cold enough to see my breath." },
    hot = { "Hot as hell today.", "The heat was brutal.", "Sweltering." },
    wind = { "Windy all day.", "The wind didn't let up all day." },
}

local TOWN_FIRST = { "I'm in {town}.", "I'm somewhere around {town}." }
local TOWN_ONE = { "Spent the day around {town}.", "Still around {town}.", "{town} again.", "Stayed near {town}." }
local TOWN_MOVE = { "Made it from {a} to {b}.", "Left {a} behind and ended up near {b}.", "Went from {a} over to {b}." }
local WOODS_TOWN = { "Spent time in the woods outside {town}.", "Out in the woods near {town} for a while." }
local WOODS = {
    any = { "Spent a lot of the day in the woods.", "Out in the woods for a while." },
    outdoors = { "Felt more like myself out in the woods." },
}

local SLEEP = {
    long = { w = { "Slept {n} hours straight.", "Slept {n} hours. Needed every one of them." },
             any = { "Slept a long time.", "Slept like the dead. Bad choice of words." } },
    mid = { w = { "Got {n} hours of sleep.", "Slept {n} hours." },
            any = { "Got some sleep.", "Slept a while." } },
    short = { w = { "Only slept {n} hours.", "{N} hours of sleep, if that." },
              any = { "Barely slept.", "Hardly slept at all." } },
}
local SLEEP_AFTER = {
    long = { "Didn't want to wake up.", "Felt almost human after." },
    short = { "Every little noise woke me up.", "Too wired to sleep." },
    insomniac = { "Same as always. Sleep doesn't come easy for me." },
}

local FOUND = { "Found {list}.", "Came across {list}.", "Checked out {list}." }
local PLACE_FLAVOR = {
    ["a pharmacy"] = { "Pills, if I'm lucky.", "I hope there's something left inside." },
    ["a gun store"] = { "Could make all the difference." },
    ["a police station"] = { "Nobody there to call for help anymore." },
    ["a grocery store"] = { "I hope there's still food on the shelves." },
    ["a hospital"] = { "I didn't like the feel of it." },
    ["a doctor's office"] = { "Bandages, maybe." },
    ["a warehouse"] = { "Big, dark and quiet. Too quiet." },
    ["a fire station"] = { "I wondered where the firefighters went." },
    ["a mechanic's garage"] = { "Tools, maybe." },
    ["a hardware store"] = { "Tools and nails. Exactly what I need." },
    ["a gas station"] = { "Snacks, at least." },
    ["a library"] = { "So quiet in there. Peaceful, almost." },
    ["a church"] = { "I didn't pray. Or maybe I did, a little." },
    ["a school"] = { "Empty classrooms are the worst." },
    ["a bakery"] = { "No bread left, I bet." },
    ["a liquor store"] = { "Tempting." },
    ["Spiffo's"] = { "Spiffo's. Of all places." },
    ["a farm"] = { "Wide open land. At least you can see them coming." },
    ["a motel"] = { "Rooms full of strangers' things." },
    ["a camping store"] = { "Tents, maybe a good knife." },
}
local PLACE_JOB = {
    policeofficer = { ["a police station"] = "Strange, walking into a station like that.", ["a gun store"] = "Felt like going back to work." },
    fireofficer = { ["a fire station"] = "Felt like coming home." },
    doctor = { ["a hospital"] = "I used to think hospitals were safe.", ["a pharmacy"] = "At least I know exactly what to look for." },
    nurse = { ["a hospital"] = "I used to work in a place like that. Hard to see it now.", ["a pharmacy"] = "At least I know exactly what to look for." },
    mechanics = { ["a mechanic's garage"] = "Felt like my old shop." },
    carpenter = { ["a hardware store"] = "Like a candy store, for me." },
    burgerflipper = { ["Spiffo's"] = "Ha. Never thought I'd miss that place." },
    chef = { ["a bakery"] = "I'd give anything to bake something real." },
    farmer = { ["a farm"] = "Felt like home." },
    parkranger = { ["a camping store"] = "Good gear in there, if you know what to look for." },
}
local PLACE_TAG = {
    bookish = { ["a library"] = "I could have stayed in there forever." },
    smoker = { ["a gas station"] = "Cigarettes, maybe.", ["a liquor store"] = "Cigarettes, maybe." },
}

local CAR = { "Drove the {car} for a while.", "Got behind the wheel of the {car}.", "Took the {car} out on the road." }
local CAR_TAG = {
    mech = { "Drove the {car}. Listened to the engine the whole way." },
    speed = { "Drove the {car} way too fast. Who's going to stop me?" },
    sunday = { "Drove the {car}. Slowly. Old habits." },
}

local KILLS_FIRST = {
    one = { "I killed one of them today, my first.", "My first kill today. I keep seeing its face." },
    few = { "I killed {n} of them today, my first ones. I keep seeing their faces." },
}
local KILLS_ONE = {
    any = { "Killed one of them today.", "Had to put one of them down." },
    brave = { "Put one of them down. Easy enough." },
    scared = { "I had to kill one of them and I can't stop shaking." },
    hard = { "Put one down." },
}
local KILLS_FEW = {
    any = { "Killed {n} of them.", "Put down {n} of them today.", "{N} of them won't get up again." },
    brave = { "Took down {n} of them, and it's getting easier.", "Dropped {n} of them. They're slow if you keep your head." },
    scared = { "I had to kill {n} of them and my hands are still shaking.", "{N} of them. I don't know how I'm still alive." },
    hard = { "{N} more of them down.", "{N} of them. Didn't feel much." },
}
local KILLS_MANY = {
    any = { "Killed {many} of them today.", "So many of them. I killed {many}." },
    brave = { "Cut through {many} of them and I'm still standing." },
    scared = { "So many of them. I killed {many} and they kept coming." },
    hard = { "{Many} of them. They just keep coming." },
}
local KILLS_PACIFIST = { "I killed {what} today. I hated every second of it.", "Had to kill {what}. It never gets easier for me." }
local JOB_KILLS = {
    policeofficer = { "Old habits: I checked every corner before going in.", "Nobody trained us for this." },
    securityguard = { "And I thought the night shift was rough." },
    fireofficer = { "Kept my breathing steady, like in training." },
    veteran = { "It's not my first war. It's the strangest one." },
    lumberjack = { "Swinging is swinging, I guess." },
}

-- the journal's own wound lines, worst first
local HURT_PATTERNS = {
    { "^Broke my (.+)%.$", "fracture" }, { "^A deep wound on the (.+)%.$", "deep" },
    { "^Glass in my (.+)%.$", "glass" }, { "^Burned my (.+)%.$", "burn" },
    { "^Cut my (.+)%.$", "cut" }, { "^Scratched on the (.+)%.$", "scratch" },
}
local HURT_ORDER = { "fracture", "deep", "glass", "burn", "cut", "scratch" }
local HURT = {
    fractureLeg = { "I broke my {part}. Every step is agony.", "My {part} is broken. I can barely walk." },
    fractureArm = { "Broke my {part}. I can hardly use it.", "My {part} is broken." },
    fracture = { "I think I broke something. My {part}." },
    deep = { "Got a deep gash on my {part} and it bled a lot.", "A deep wound on my {part}, and so much blood." },
    glass = { "Got glass stuck in my {part}.", "Picked up a shard of glass in my {part}." },
    burn = { "Burned my {part}.", "My {part} got burned." },
    cut = { "Cut my {part}.", "Sliced my {part} open on something." },
    scratch = { "Got scratched on the {part}.", "Something scratched my {part}." },
}
local SCRATCH_AFTER = {
    any = { "I keep checking it.", "Just a scratch. I keep telling myself that." },
    hard = { "Just a scratch." },
    scared = { "I can't stop looking at it." },
}
local MEDIC = { "At least I know how to clean a wound.", "I cleaned it properly. I know what infection does." }
local HEMO = { "The blood. I nearly fainted.", "I can't stand the sight of blood. Today was bad." }

local BITE = { "One of them bit me. My {part}.", "I got bitten. {Part}." }
local BITE_AFTER = {
    any = { "I know what that means.", "Everybody knows what a bite means. Maybe I'll be the lucky one." },
    brave = { "I know what that means. I'm not going to cry about it.", "If this is it, I'm going down swinging." },
    scared = { "I can't stop shaking. I know what happens now.", "I don't want to turn into one of them." },
    hard = { "I've seen how this ends." },
    medic = { "I know exactly what a bite does. I've seen it." },
}
local BITTEN_LATER = { "I keep looking at the bite.", "Every hour I wait for the fever." }

local SICK = {
    bitten = { "I feel sick. I know it's the bite.", "Fever. It's starting, isn't it." },
    prone = { "Sick again. Of course I am.", "Caught something, as usual." },
    any = { "I feel sick.", "Felt sick most of the day.", "Fever, I think." },
}

-- the game's skill ids (PerkFactory) -> how it feels to get better at them
local SKILL = {
    Sprinting = "I'm getting faster on my feet", Lightfoot = "I'm learning to walk quietly",
    Nimble = "I'm getting better at slipping past them", Sneak = "I'm learning to move without being seen",
    Fitness = "I can keep going longer before I'm out of breath", Strength = "I'm getting stronger",
    Axe = "The axe feels lighter in my hands", Blunt = "I'm getting the hang of swinging something heavy",
    SmallBlunt = "I'm getting handy with a hammer", LongBlade = "I'm getting better with a long blade",
    SmallBlade = "I'm getting quick with a knife", Spear = "I'm getting better with a spear",
    Maintenance = "I'm learning to keep my gear in shape", Woodwork = "My carpentry is getting better",
    Cooking = "I'm getting better at cooking with whatever I find", Farming = "I'm learning how to grow things",
    Doctor = "My first aid is getting better", Electricity = "I'm figuring out the wiring",
    MetalWelding = "I'm getting better at metalwork", Mechanics = "I'm learning my way around engines",
    Tailoring = "My stitches are getting neater", Aiming = "My aim is getting better",
    Reloading = "I can reload faster now", Fishing = "I'm getting better at fishing",
    Trapping = "I'm learning to set traps", PlantScavenging = "I'm getting better at finding things outdoors",
    Tracking = "I'm learning to read tracks", Husbandry = "I'm getting better with animals",
    FlintKnapping = "I'm learning to knap stone", Masonry = "I'm learning to work with stone",
    Pottery = "I'm getting the hang of pottery", Carving = "My carving is getting better",
    Butchering = "I'm getting better at butchering", Glassmaking = "I'm learning to work glass",
    Blacksmith = "I'm learning my way around a forge",
}

local BASE = { "I've made this place my base.", "Found somewhere to call home, for now.", "Settled in here. Not much, but it's mine." }
local PHOTO = {
    "Took a picture today. Someone should remember what this looked like.",
    "I took a photo. Strange, keeping a record of all this.",
    "Snapped a picture. I don't know who I'm keeping it for.",
}
local PHOTO_OF = { "Took a picture: {photo}.", "I took a photo of {photo}. Worth remembering." }
local BASE_JOB = {
    carpenter = "Tomorrow I start boarding up the windows.",
    constructionworker = "Tomorrow I start boarding up the windows.",
    electrician = "If I can find a generator, this place could really work.",
    engineer = "If I can find a generator, this place could really work.",
    farmer = "There's room out back for a garden.",
}

-- strongest feelings first
local MOOD_ORDER = { "PANIC", "PAIN", "SICK", "STRESS", "UNHAPPY", "HYPOTHERMIA", "HYPERTHERMIA", "HAS_A_COLD",
    "DRUNK", "HUNGRY", "THIRST", "ENDURANCE", "TIRED", "WET", "BORED", "ANGRY" }
local MOOD = {
    PANIC = { any = { "My heart was pounding so hard I thought they'd hear it.", "I panicked today. Really panicked." },
              brave = { "Even I got scared today." }, scared = { "I panicked. I can't stop seeing their faces." } },
    PAIN = { any = { "Everything hurts.", "The pain doesn't let up." }, hard = { "Hurts. I'll live." } },
    SICK = { any = { "My stomach's been turning all day." } },
    STRESS = { any = { "I'm wound so tight I can't sit still.", "My nerves are shot." },
               smoker = { "I'd give anything for a cigarette.", "I need a smoke. Badly." } },
    UNHAPPY = { any = { "I keep thinking about how things used to be.", "Some days I don't see the point.",
                        "I miss people. Just talking to someone." } },
    HYPOTHERMIA = { any = { "I couldn't get warm.", "Cold to the bone." } },
    HYPERTHERMIA = { any = { "The heat is killing me.", "Sweated through everything." } },
    HAS_A_COLD = { any = { "Caught a cold. Sniffling and sneezing, just when I need to be quiet." } },
    DRUNK = { any = { "Had a drink. Or a few.", "Drank too much. It helped, for a while." } },
    HUNGRY = { any = { "I'm so hungry.", "My stomach won't stop growling." },
               cook = { "I keep dreaming about a real meal." }, hearty = { "Starving, as usual." } },
    THIRST = { any = { "So thirsty my head hurts." } },
    ENDURANCE = { any = { "My legs are done.", "Completely worn out." },
                  fitness = { "All those years of training and I'm still out of breath." } },
    TIRED = { any = { "So tired.", "I could sleep for a week." } },
    WET = { any = { "Soaked to the bone." } },
    BORED = { any = { "Nothing to do but wait.", "The boredom is its own kind of hell." },
              bookish = { "I'd kill for a good book." },
              unemployed = { "I used to have all the time in the world. Still do, I guess." } },
    ANGRY = { any = { "I'm so angry. At everything, at nobody." } },
}

local MEMORY = {
    any = {
        "I miss coffee. Real, hot coffee.", "I miss hot showers.", "I keep thinking about my family.",
        "I wonder if anyone's still out there.", "I miss music. Real music, not the emergency broadcast.",
        "I keep waiting for the phone to ring.", "I keep thinking about the last normal day. I didn't even notice it.",
    },
    burglar = { "Funny. I used to sneak into houses. Now I do it to stay alive." },
    burgerflipper = { "Never thought I'd miss the grease and the screaming kids." },
    carpenter = { "I miss building things that weren't barricades." },
    chef = { "I'd give anything for a proper kitchen and some fresh herbs." },
    constructionworker = { "All those houses I helped build. Now they're full of the dead." },
    doctor = { "I took an oath. I don't know what it means now." },
    electrician = { "When the power goes, nobody's coming to fix it. Not even me." },
    engineer = { "I keep running numbers in my head. None of them add up." },
    farmer = { "Somebody should be tending my fields." },
    fireofficer = { "I keep wondering what happened to the rest of my crew." },
    fisherman = { "I miss the river at dawn." },
    fitnessinstructor = { "I used to tell people to push through the pain. Easy to say." },
    lumberjack = { "I miss the smell of fresh-cut pine." },
    mechanics = { "I keep hearing engines that aren't there." },
    metalworker = { "I miss the shop. The noise, the heat, the sparks." },
    nurse = { "I keep thinking about the patients I left on the ward." },
    parkranger = { "The park must be full of them now." },
    policeofficer = { "I wonder what happened to my partner." },
    rancher = { "I hope somebody let the animals out." },
    repairman = { "Everything's broken now. Job security, I guess." },
    securityguard = { "I used to guard an empty building all night and call it boring." },
    smither = { "I miss the forge." },
    tailor = { "I miss my sewing machine. The hum of it." },
    unemployed = { "Before all this I had no idea what to do with my days. Now I know." },
    veteran = { "The nightmares are back. New ones." },
}

local QUIET = { "Quiet day.", "Nothing much happened today.", "Kept my head down today.", "Didn't do much today." }
local QUIET_SO_FAR = { "Quiet so far.", "Nothing much yet today.", "Keeping my head down so far today." }

local CLOSE = {
    any = { "Tomorrow's another day.", "I'll keep going.", "That's all for today.", "Lights out." },
    brave = { "Tomorrow I go further.", "They won't get me.", "Still standing." },
    scared = { "I hope I wake up tomorrow.", "I'll stay hidden as long as I can.", "Please let tomorrow be quiet." },
    hard = { "Same again tomorrow.", "That's the day." },
}
local CLOSE_BITTEN = { "If this is my last entry, at least I tried.", "Whatever happens, I was here." }
local CLOSE_FIRST = { "I'll write again tomorrow. If there is one." }
local CLOSE_LATE = { "Going to try to sleep now.", "Time to find somewhere safe to sleep." }

-- ----------------------------------------------------------------------------- the entry

local function many(n)
    if n <= 15 then return "more than ten" end
    if n <= 25 then return "about twenty" end
    return "dozens"
end

local function weatherOf(wx)
    if type(wx) ~= "table" then return nil end
    for _, k in ipairs(WEATHER_ORDER) do
        if wx[k] then return k end
    end
    return nil
end

--- Writes one page's diary entry. ctx: seed, day, final (the day is over), hour (now, for a day
--- still going), full / first name, job (profession id) and jobLabel, tone ("brave", "scared",
--- "hard" or nil), tags (job id and trait words), page, bittenBefore, killsBefore.
function KL.diaryEntry(ctx)
    local p = ctx.page
    local lines = p.lines or {}
    local vars = { day = ctx.day, full = ctx.full }
    local pieces = {}
    -- order: where it goes on the page; drop: dropped first (highest) when the entry runs long
    local function add(text, order, drop)
        if text and text ~= "" then table.insert(pieces, { s = text, o = order, drop = drop }) end
    end

    -- what the page's lines say, by kind (the journal adds them in the order they happened)
    local sleep, car, bite, sick, base, photo = nil, nil, nil, nil, nil, nil
    local places, hurts, skills = {}, {}, {}
    local placeAt, afterTravel = nil, 0
    for i, l in ipairs(lines) do
        local k, s = l.k, tostring(l.s or "")
        if k == "sleep" then
            if not sleep then sleep = { n = tonumber(string.match(s, "(%d+)")) or 0, w = l.w, i = i } end
        elseif k == "place" then
            local what = string.match(s, "^Found (.+)%.$")
            if what and #places < 3 then
                table.insert(places, what)
                placeAt = placeAt or i
            end
            afterTravel = i
        elseif k == "car" then
            local name = string.match(s, "^Drove the (.+)%.$")
            if name and not car then car = { name = name, i = i } end
            afterTravel = i
        elseif k == "hurt" then
            for _, pat in ipairs(HURT_PATTERNS) do
                local part = string.match(s, pat[1])
                if part then
                    table.insert(hurts, { kind = pat[2], part = part, i = i })
                    break
                end
            end
        elseif k == "bite" then
            if not bite then bite = { part = string.match(s, "^Bitten on the (.+)%.$") or "arm", i = i } end
        elseif k == "sick" then
            if not sick then sick = { i = i } end
        elseif k == "skill" then
            local name = string.match(s, "^(.+) is now level %d+%.$")
            table.insert(skills, { id = l.a, name = name, i = i })
        elseif k == "base" then
            if not base then base = { i = i } end
        elseif k == "photo" then
            if not photo then photo = { what = string.match(s, "^Took a photo: (.+)%.$"), i = i } end
        end
    end
    local kills = p.kills or 0
    local happened = #lines > 0 or kills > 0
    local at = type(p.at) == "table" and p.at or {}
    local wx = weatherOf(p.wx)
    local bitten = bite ~= nil or ctx.bittenBefore

    -- the first words (a plain "Day 12." is the first thing to go on a busy page)
    local sleepOpens = false
    if ctx.day == 1 then
        add(pick(ctx, "first", OPEN_FIRST), -10)
        if ctx.full and ctx.full ~= "" and chance(ctx, "name", 60) then
            add(fill("My name is {full}, in case anyone finds this.", vars), -9, 5)
        end
        add(BEFORE[ctx.job] or (ctx.jobLabel and ("Before all this, my job was " .. string.lower(ctx.jobLabel) .. ".")), -8)
        if wx and chance(ctx, "wxline", 60) then add(pick(ctx, "wx", WEATHER[wx]), -7, 2) end
    elseif MILESTONE[ctx.day] then
        add(MILESTONE[ctx.day], -10)
        if wx and chance(ctx, "wxline", 60) then add(pick(ctx, "wx", WEATHER[wx]), -7, 2) end
    elseif wx and chance(ctx, "wxopen", 75) then
        add(pick(ctx, "wx", WEATHER[wx]), -10, 1.5)
    elseif sleep and sleep.i == 1 and chance(ctx, "sleepopen", 60) then
        sleepOpens = true
    else
        add(fill(voiced(ctx, "open", OPEN), vars), -10, 2.5)
    end

    -- where they were
    if #at >= 2 then
        vars.a, vars.b = at[1], at[#at]
        add(fill(pick(ctx, "move", TOWN_MOVE), vars), -5)
    elseif #at == 1 then
        vars.town = at[1]
        if ctx.day == 1 then
            add(fill(pick(ctx, "here", TOWN_FIRST), vars), -5, 2)
        elseif p.woods and chance(ctx, "woodstown", 50) then
            add(fill(pick(ctx, "woodsT", WOODS_TOWN), vars), -5, 2)
        elseif chance(ctx, "town", 45) then
            add(fill(pick(ctx, "town", TOWN_ONE), vars), -5, 2)
        end
    elseif p.woods then
        add(voiced(ctx, "woods", WOODS), -5, 2)
    end
    if not happened and #at < 2 then add(pick(ctx, "quiet", ctx.final and QUIET or QUIET_SO_FAR), -4) end

    -- sleep
    if sleep then
        local how = sleep.n >= 9 and "long" or (sleep.n >= 6 and "mid" or "short")
        vars.n, vars.N = count(sleep.n), upper1(count(sleep.n))
        local t = sleep.w and sleep.n > 0 and SLEEP[how].w or SLEEP[how].any
        local text = fill(pick(ctx, "sleep", t), vars)
        local after = (how == "short" and hasTag(ctx, "insomniac")) and SLEEP_AFTER.insomniac or SLEEP_AFTER[how]
        if after and chance(ctx, "sleepafter", 50) then text = text .. " " .. pick(ctx, "sleepA", after) end
        add(text, sleepOpens and -10 or sleep.i)
    end

    -- places found
    if #places > 0 then
        vars.list = join(places)
        add(fill(pick(ctx, "found", FOUND), vars), placeAt)
        local first = places[1]
        local flavor = PLACE_JOB[ctx.job] and PLACE_JOB[ctx.job][first]
        if not flavor then
            for _, tag in ipairs(ctx.tags or {}) do
                flavor = flavor or (PLACE_TAG[tag] and PLACE_TAG[tag][first])
            end
        end
        if not flavor and PLACE_FLAVOR[first] and chance(ctx, "placeflavor", 50) then
            flavor = pick(ctx, "placeF", PLACE_FLAVOR[first])
        end
        add(flavor, placeAt + 0.1, 3)
    end

    -- driving
    if car then
        vars.car = car.name
        local t = CAR
        for _, tag in ipairs({ "speed", "sunday", "mech" }) do
            if hasTag(ctx, tag) then t = CAR_TAG[tag] end
        end
        add(fill(pick(ctx, "car", t), vars), car.i)
    end

    -- the dead they put down: before the first wound (that's how they get hurt), else after the travelling
    local fightAt = bite and bite.i or nil
    for _, h in ipairs(hurts) do fightAt = math.min(fightAt or h.i, h.i) end
    local killsAt = fightAt and (fightAt - 0.5) or (math.max(afterTravel, sleep and sleep.i == 1 and 1 or 0) + 0.5)
    if kills > 0 then
        vars.n, vars.N = count(kills), upper1(count(kills))
        local text
        if not ctx.killsBefore then
            text = pick(ctx, "firstkill", kills == 1 and KILLS_FIRST.one or KILLS_FIRST.few)
        elseif hasTag(ctx, "pacifist") then
            vars.what = kills == 1 and "one of them" or (kills <= 10 and count(kills) or many(kills)) .. " of them"
            text = pick(ctx, "pacifist", KILLS_PACIFIST)
        elseif kills == 1 then
            text = voiced(ctx, "kill1", KILLS_ONE)
        elseif kills <= 10 then
            text = voiced(ctx, "kills", KILLS_FEW)
        else
            vars.many = many(kills)
            vars.Many = upper1(vars.many)
            text = voiced(ctx, "killsmany", KILLS_MANY)
        end
        add(fill(text, vars), killsAt)
        if JOB_KILLS[ctx.job] and chance(ctx, "jobkill", 40) then add(pick(ctx, "jobkillL", JOB_KILLS[ctx.job]), killsAt + 0.1, 3) end
    end

    -- wounds: the worst two kinds, the same kind on two parts in one sentence
    local byKind = {}
    for _, h in ipairs(hurts) do
        local g = byKind[h.kind]
        if not g then
            g = { parts = {}, i = h.i }
            byKind[h.kind] = g
        end
        local dup = false
        for _, part in ipairs(g.parts) do dup = dup or part == h.part end
        if not dup and #g.parts < 2 then table.insert(g.parts, h.part) end
    end
    local told, medicSaid = 0, false
    for _, kind in ipairs(HURT_ORDER) do
        local g = byKind[kind]
        if g and told < 2 then
            told = told + 1
            local key = kind
            local part = g.parts[1]
            if kind == "fracture" then
                if string.find(part, "thigh") or string.find(part, "shin") or string.find(part, "foot") then key = "fractureLeg"
                elseif string.find(part, "hand") or string.find(part, "arm") then key = "fractureArm" end
            elseif kind == "scratch" or kind == "cut" or kind == "burn" then
                part = join(g.parts)
            end
            vars.part = part
            add(fill(pick(ctx, "hurt" .. kind, HURT[key]), vars), g.i)
            if kind == "scratch" and chance(ctx, "scratchafter", 70) then add(voiced(ctx, "scratchA", SCRATCH_AFTER), g.i + 0.1, 3) end
            if (kind == "deep" or kind == "cut") and hasTag(ctx, "hemo") then add(pick(ctx, "hemo", HEMO), g.i + 0.2, 3) end
            if hasTag(ctx, "medic") and not medicSaid and kind ~= "fracture" and chance(ctx, "medic", 55) then
                medicSaid = true
                add(pick(ctx, "medicL", MEDIC), g.i + 0.3, 3)
            end
        end
    end

    -- a bite: always said, and what they make of it
    if bite then
        vars.part, vars.Part = bite.part, upper1(bite.part)
        add(fill(pick(ctx, "bite", BITE), vars) .. " " .. voiced(ctx, "biteA", BITE_AFTER), bite.i)
    end

    if sick then
        local t = bitten and SICK.bitten or (hasTag(ctx, "prone") and SICK.prone or SICK.any)
        add(pick(ctx, "sick", t), sick.i)
    end

    -- getting better at things: the two newest skills
    local said, seen = {}, {}
    for i = #skills, 1, -1 do
        local s = skills[i]
        local key = s.id or s.name or ""
        if not seen[key] and #said < 2 then
            seen[key] = true
            local words = s.id and SKILL[s.id]
            if not words and s.name then words = "I'm getting better at " .. string.lower(s.name) end
            if words then table.insert(said, 1, { w = words, i = s.i }) end
        end
    end
    if #said == 1 then
        add(said[1].w .. ".", said[1].i, 2)
    elseif #said == 2 then
        local second = said[2].w
        if not string.find(second, "^I ") and not string.find(second, "^I'") then second = string.lower(string.sub(second, 1, 1)) .. string.sub(second, 2) end
        add(said[1].w .. ", and " .. second .. ".", said[2].i, 2)
    end

    if base then
        add(pick(ctx, "base", BASE), base.i)
        if BASE_JOB[ctx.job] then add(BASE_JOB[ctx.job], base.i + 0.1, 3) end
    end

    if photo then
        if photo.what then
            vars.photo = string.lower(string.sub(photo.what, 1, 1)) .. string.sub(photo.what, 2)
            add(fill(pick(ctx, "photoof", PHOTO_OF), vars), photo.i, 2)
        else
            add(pick(ctx, "photo", PHOTO), photo.i, 2)
        end
    end

    -- how it felt: the strongest feeling of the day, sometimes a second one
    local peak = type(p.peak) == "table" and p.peak or {}
    local moods = 0
    for _, key in ipairs(MOOD_ORDER) do
        local lvl = peak[key]
        if lvl and lvl >= 2 and moods < 2 and not (key == "SICK" and sick) then
            if moods == 0 or chance(ctx, "mood2", 50) then
                add(voiced(ctx, "mood" .. key, MOOD[key]), 50 + moods, moods == 0 and 1 or 4)
                moods = moods + 1
            end
        end
    end
    if ctx.bittenBefore and not bite and not sick and chance(ctx, "bittenlater", 60) then
        add(pick(ctx, "bittenL", BITTEN_LATER), 49, 1)
    end

    -- the last words of a finished day
    if ctx.final then
        if ctx.day == 1 then
            add(pick(ctx, "close1", CLOSE_FIRST), 90, 3)
        elseif bitten then
            -- no "they won't get me" from someone who's been bitten
            if bite or chance(ctx, "closebitten", 70) then add(pick(ctx, "closeB", CLOSE_BITTEN), 90, 3) end
        elseif chance(ctx, "close", 60) then
            add(voiced(ctx, "closeL", CLOSE), 90, 3)
        end
    elseif (ctx.hour or 0) >= 21 and chance(ctx, "late", 50) then
        add(pick(ctx, "lateL", CLOSE_LATE), 90, 3)
    end

    local function total()
        local n = 0
        for _, pc in ipairs(pieces) do n = n + sentences(pc.s) end
        return n
    end

    -- a memory of before, on quiet pages and now and then
    if ctx.day > 1 and (total() < 4 or chance(ctx, "memory", 20)) then
        local own = MEMORY[ctx.job]
        local t = (own and chance(ctx, "ownmemory", 50)) and own or MEMORY.any
        add(pick(ctx, "memoryL", t), 60, 6)
    end

    -- a few sentences, more on a busy day: the extras go first when it runs long
    while total() > 9 do
        local worst, at2 = nil, nil
        for idx, pc in ipairs(pieces) do
            if pc.drop and (not worst or pc.drop > worst.drop or (pc.drop == worst.drop and pc.o > worst.o)) then
                worst, at2 = pc, idx
            end
        end
        if not at2 then break end
        table.remove(pieces, at2)
    end
    table.sort(pieces, function(a, b) return a.o < b.o end)
    local out = {}
    for _, pc in ipairs(pieces) do table.insert(out, pc.s) end
    return KL.diaryClip(table.concat(out, " "), 900)
end
