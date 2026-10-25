--[[
    Knox Link — the danger radar: the zombies around the character, as far as the character knows.

    radar (5x a second while a screen shows it, sent only when something changed):
      z    = { { i = id, x = dx, y = dy, s = "seen" | "heard" | "lost", b = "idle" | "chase" | "attack",
                 a = seconds since it was lost (lost only), f = floors above (+) or below (-), when not 0 }, ... }
             nearest first; i stays the same while the radar follows that zombie
      yaw  = where the radar's "up" points, in degrees: Project Viewpoint's camera in its 3D view
             (KnoxLink_viewYaw), else the way the character faces
      c    = half the width of the view, in degrees: further round than that is "outside the view"
             (in 3D from the camera's vertical field of view, KnoxLink_viewFov when the server
             add-on has it, else Viewpoint's defaults, across the screen's width)
      r    = radius, in squares (a screen picks 10, 20 or 30 with the "radar" command)
      near = { a = angle from the view (0 ahead, 90 right, -90 left, 180 behind), d = squares, i = id, b = ... }:
             the closest zombie the character knows of within 3 squares, outside the view

    Axes: dx, dy are world squares from the character: +x is east and +y is south (the world map's
    right and down). Angles turn from +x towards +y (clockwise on the map), like
    IsoGameCharacter:getDirectionAngle() and Viewpoint's camera, which looks along (cos yaw, sin yaw).

    What the character knows, by the game's own rules:
      seen   The game draws the zombie for this character (its target alpha, set by IsoPlayer.updateLOS
             and IsoGameCharacter.TestIfSeen): a line of sight (walls, closed doors and drawn curtains
             block it), enough light, the view distance, and the view cone, which the game narrows in the dark,
             when tired, drunk or panicking and widens for Eagle Eyed (by day) and Night Vision (at
             night); Short Sighted sees less far. The game's top-down view also draws a zombie that is
             only sensed close by: that one counts as heard. Viewpoint's 3D view opens the game's cone
             to the full circle (LightingJNI.calculateVisionCone) because its camera shows what's
             ahead, so there a zombie must also be on screen: inside the camera's field of view,
             worked out like Viewpoint's own culling (ModelCull: the vertical field of view across
             the screen's width).
      heard  Close by with nothing in between: the character's detection range
             (IsoGameCharacter.getDetectionRange: 3.5 squares, Keen Hearing +3, Hard of Hearing -1,
             Deaf 2, less when tired or drunk), the game's own rule for sensing someone behind you.
             Or making noise within the radius the game sends a zombie's sounds to players over
             (IsoZombie.ZombieSound): groans 15 squares, a zombie after someone or attacking (its
             "lock target" vocals), or banging on a door or window, 40. Those distances shrink with
             the character's hearing (getHearDistanceModifier: Hard of Hearing x4.5, earmuffs and
             helmets) and in rain or fog (getWeatherHearingMultiplier); a deaf character hears no
             sounds (FMODParameterUtils skips deaf listeners). Through a wall or a floor a sound
             carries half as far (our stand-in for the game's sound occlusion), and an idle groan
             doesn't carry between floors. A heard zombie is placed on its square, not to the inch.
      lost   Seen or heard in the last few seconds: where it was last.
    The character notices nothing asleep or dead. With immersion off, every zombie in range is seen.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try, round = KL.try, KL.round
local floor, abs, sqrt = math.floor, math.abs, math.sqrt
local atan2, atan, tan, deg, rad = math.atan2, math.atan, math.tan, math.deg, math.rad

local LOST_MS = 6000            -- how long the last known place of a zombie stays on the radar
local NEAR = 3                  -- squares: "close behind you"
local MAX_DOTS = 80             -- nearest first; nobody needs every zombie of a horde
local KEEPALIVE_MS = 3000       -- sent at least this often, even when nothing changed
local GROAN, LOUD = 15, 40      -- IsoZombie.ZombieSound radii: Idle, Lunge
local CONE = 72                 -- half the game's own view cone at its widest everyday value (144)
local FOV_FIRST, FOV_THIRD = 103, 60   -- Viewpoint's default vertical fields of view
local RANGES = { [10] = true, [20] = true, [30] = true }

KL.radarRadius = KL.radarRadius or 20

-- The zombie's state, by name (State.getName is the class name). Lunging is the rush before the bite.
local ATTACKING = {
    AttackState = true, AttackNetworkState = true, LungeState = true, LungeNetworkState = true,
    AttackVehicleState = true,
}

local function isDeaf(player)
    -- Build 42 names the trait in CharacterTrait; nothing nil may reach Java
    if CharacterTrait and CharacterTrait.DEAF then
        local ok, deaf = pcall(function() return player:hasTrait(CharacterTrait.DEAF) end)
        if ok and deaf ~= nil then return deaf == true end
    end
    local ok, deaf = pcall(function() return player:HasTrait("Deaf") end)
    return ok and deaf == true
end

--- An angle in degrees, brought into -180..180.
local function wrap(a)
    return a - floor((a + 180) / 360) * 360
end

-- ----------------------------------------------------------------------------- the view

local aspect, aspectAt = 16 / 9, -1e9
local thirdPerson, thirdAt = false, -1e9

--- The screen's width over its height (Viewpoint's field of view is set by the height).
local function screenAspect(now)
    if now - aspectAt > 2000 then
        aspectAt = now
        local core = getCore()
        local w, h = try(core, "getScreenWidth", 0), try(core, "getScreenHeight", 0)
        if w > 0 and h > 0 then aspect = w / h end
    end
    return aspect
end

--- Where "up" points on the radar, half the width of what the character sees there (degrees),
--- and whether that's Viewpoint's 3D view.
local function viewOf(player, now)
    local yaw = KnoxLink_viewYaw and KnoxLink_viewYaw() or nil
    if type(yaw) ~= "number" then
        return try(player, "getDirectionAngle", 0), CONE, false
    end
    -- the camera's vertical field of view (the server add-on may tell; else Viewpoint's defaults)
    local fov = KnoxLink_viewFov and KnoxLink_viewFov() or nil
    if type(fov) ~= "number" or fov <= 0 then
        if now - thirdAt > 1000 then
            thirdAt = now
            local s = KL.viewState and KL.viewState()
            thirdPerson = s ~= nil and s.v == "third"
        end
        fov = thirdPerson and FOV_THIRD or FOV_FIRST
    end
    return yaw, deg(atan(tan(rad(fov / 2)) * screenAspect(now))), true
end

-- ----------------------------------------------------------------------------- the zombies

--- What the zombie is doing, as the character would read it, and whether it's making a racket.
local function behaviour(player, zed)
    local state = zed:getCurrentStateName()
    local target = zed:getTarget()
    local attacking = state ~= nil and ATTACKING[state] == true
    if target == player then
        return attacking and "attack" or "chase", true
    end
    -- after someone else, attacking something, or banging on a door or window
    return "idle", target ~= nil or attacking or state == "ThumpState"
end

--- Lying still on the floor like the dead, or a body the game only raised to be dragged.
local function playsDead(zed)
    if zed:isFakeDead() then return true end
    return zed.isReanimatedForGrappleOnly ~= nil and zed:isReanimatedForGrappleOnly() == true
end

local known = {}                -- zombie -> what the character knows of it: { i, x, y, lz, b, s, at }
local nextId, owner = 1, nil
local lastSig, lastSentAt = nil, 0

KL.addCollector("radar", 200, function(player)
    local now = getTimestampMs()
    if player ~= owner then known, owner = {}, player end   -- a new character knows nothing yet
    local px, py = player:getX(), player:getY()
    local level = floor(player:getZ())
    local pn = player:getPlayerNum()
    local yaw, cone, in3d = viewOf(player, now)
    local r = KL.radarRadius
    local everything = not KL.immersive()

    if not player:isDead() and not try(player, "isAsleep", false) then
        local hear = 0
        if not isDeaf(player) then
            hear = try(player, "getWeatherHearingMultiplier", 1) / math.max(0.1, try(player, "getHearDistanceModifier", 1))
        end
        local groan, loud = GROAN * hear, LOUD * hear
        -- (very tired and drunk, it can drop below zero: then nothing is sensed)
        local detect = math.max(0, try(player, "getDetectionRange", 3.5))
        local detect2, r2 = detect * detect, r * r
        local zombies = getCell():getZombieList()
        for n = 0, zombies:size() - 1 do
            local zed = zombies:get(n)
            local zx, zy = zed:getX(), zed:getY()
            local dx, dy = zx - px, zy - py
            local d2 = dx * dx + dy * dy
            if d2 <= r2 then
                local df = floor(zed:getZ()) - level
                local sq = (df >= -1 and df <= 1) and zed:getCurrentSquare() or nil
                if zed:isDead() then
                    known[zed] = nil
                elseif sq and not playsDead(zed) then
                    local how = everything and "seen" or nil
                    -- seen: the game draws it for this character (and, in 3D, it's on screen)
                    if not how and zed:getTargetAlpha(pn) > 0.5 then
                        if in3d then
                            if abs(wrap(deg(atan2(dy, dx)) - yaw)) <= cone then how = "seen" end
                        elseif sq:isCanSee(pn) or (zed.canSeeHeadSquare ~= nil and zed:canSeeHeadSquare(player)) then
                            how = "seen"
                        end
                    end
                    local b, noisy = behaviour(player, zed)
                    -- heard: sensed close by, or its noise reaches the character
                    if not how then
                        local clear = sq:isCouldSee(pn)
                        if df == 0 and clear and d2 < detect2 then
                            how = "heard"
                        else
                            local reach = noisy and loud or (df == 0 and groan or 0)
                            if df ~= 0 or not clear then reach = reach / 2 end
                            if d2 < reach * reach then how = "heard" end
                        end
                    end
                    if how then
                        local k = known[zed]
                        if not k then
                            k = { i = nextId }
                            nextId = nextId + 1
                            known[zed] = k
                        end
                        -- heard: about there, on its square
                        if how == "heard" then zx, zy = floor(zx) + 0.5, floor(zy) + 0.5 end
                        k.x, k.y, k.lz, k.b, k.s, k.at = zx, zy, level + df, b, how, now
                    end
                end
            end
        end
    end

    -- what goes out: everything known now, then what was lost lately; the closest one behind
    local list, gone = {}, {}
    local near, nearD2 = nil, NEAR * NEAR
    for zed, k in pairs(known) do
        local age = now - k.at
        if age >= LOST_MS then
            table.insert(gone, zed)
        else
            local dx, dy, df = k.x - px, k.y - py, k.lz - level
            local e = { i = k.i, x = round(dx, 1), y = round(dy, 1), s = age == 0 and k.s or "lost", b = k.b }
            e.d2 = dx * dx + dy * dy
            if age > 0 then e.a = round(age / 1000, 1) end
            if df ~= 0 then e.f = df end
            table.insert(list, e)
            if age == 0 and df == 0 and e.d2 <= nearD2 then
                local rel = wrap(deg(atan2(dy, dx)) - yaw)
                if abs(rel) > cone then
                    near, nearD2 = { a = round(rel, 0), d = round(sqrt(e.d2), 1), i = k.i, b = k.b }, e.d2
                end
            end
        end
    end
    for _, zed in ipairs(gone) do known[zed] = nil end
    table.sort(list, function(p, q) return p.d2 < q.d2 end)
    while #list > MAX_DOTS do table.remove(list) end

    yaw, cone = round(wrap(yaw), 1), round(cone, 0)
    local sig = { round(yaw, 0), cone, r, near and (near.i .. "/" .. near.a .. "/" .. near.b) or "" }
    for _, e in ipairs(list) do
        e.d2 = nil
        sig[#sig + 1] = e.i .. e.s .. e.b .. e.x .. "," .. e.y
    end
    sig = table.concat(sig, ";")
    if sig == lastSig and now - lastSentAt < KEEPALIVE_MS then return nil end
    lastSig, lastSentAt = sig, now
    return { z = list, yaw = yaw, c = cone, r = r, near = near }
end)

-- A screen picks the radar's range (squares); the game only looks that far.
KL.addHandler("radar", function(player, a)
    local r = floor(tonumber(a.r) or 0)
    if not RANGES[r] then return false, "Unknown radar range" end
    KL.radarRadius = r
    lastSig = nil
    KL.refresh("radar")
    return true
end)
