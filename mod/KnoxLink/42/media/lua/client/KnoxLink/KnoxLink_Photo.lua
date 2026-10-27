--[[
    Knox Link — the scrapbook: photos of the game for the journal.

    A screen (or the "Take a photo" shortcut) asks for a photo; the server add-on takes the next
    frame as the game shows it, before the game's own windows and bars are drawn (Photo.java),
    and keeps it in the save. It goes on today's journal page (ph = { {id, c = caption, h, mi}, ... }),
    with a line the diary writers can use.

    In immersion the character needs a camera: the game's own cameras, found as loot. A
    disposable one has a roll of 27 shots; the others don't run out.

      photo         {c = caption}    takes one
      photoCaption  {id, c}          changes its caption
      photoDelete   {id}             throws it away
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_Journal"

local KL = KnoxLink
local try = KL.try

local CAMERAS = { "Base.CameraExpensive", "Base.Camera", "Base.CameraDisposable" }
local ROLL = 27

--- The camera the character would use (a disposable one only with shots left), and its shots left (disposable).
local function camera(player)
    local inv = player:getInventory()
    local spent = false
    for _, t in ipairs(CAMERAS) do
        local list = try(inv, "getAllTypeRecurse", nil, t)
        if list then
            for i = 0, list:size() - 1 do
                local it = list:get(i)
                if t ~= "Base.CameraDisposable" then return it, nil end
                local used = tonumber(it:getModData().KnoxLinkShots) or 0
                if used < ROLL then return it, ROLL - used end
                spent = true
            end
        end
    end
    return nil, nil, spent
end

local function clean(s)
    s = tostring(s or ""):gsub("[\r\n]", " ")
    s = s:gsub("^%s+", ""):gsub("%s+$", ""):gsub("[%.!]+$", "")
    if #s > 120 then s = s:sub(1, 120) end
    return s ~= "" and s or nil
end

local taken = 0

KL.addHandler("photo", function(player, a)
    if not KnoxLink_photo then return false, "Needs the Knox Link Server add-on" end
    if player:isDead() then return false, "Not now" end
    local cam, left, spent = nil, nil, false
    if KL.immersive() then
        cam, left, spent = camera(player)
        if not cam then return false, spent and "Your disposable camera is used up" or "You need a camera" end
    end
    if not KL.journalPhotoRoom(player) then return false, "That's enough photos for one day" end
    taken = taken + 1
    local day = math.floor(player:getHoursSurvived() / 24) + 1
    local id = tostring(KL.journalId(player)) .. "-d" .. day .. "-" .. tostring(getTimestampMs() % 100000000) .. taken
    id = id:gsub("[^%w_%-]", "")
    local why = KnoxLink_photo(id)
    if why then return false, why end
    if left then cam:getModData().KnoxLinkShots = ROLL - left + 1 end
    KL.journalPhoto(player, id, clean(a.c))
    -- (the screen says "Photo taken"; this is what it adds)
    if left then
        if left == 1 then return true, "that was the last shot" end
        return true, (left - 1) .. " shots left"
    end
    return true
end)

KL.addHandler("photoCaption", function(player, a)
    if not KL.journalPhotoEdit(player, tostring(a.id or ""), clean(a.c)) then return false, "That photo is gone" end
    return true
end)

KL.addHandler("photoDelete", function(player, a)
    local id = tostring(a.id or "")
    if not KL.journalPhotoEdit(player, id, false) then return false, "That photo is gone" end
    if KnoxLink_photoDelete then KnoxLink_photoDelete(id) end
    return true
end)

-- the world is on the screen and the game's UI isn't yet: when a photo is booked, it's this frame
Events.OnPreUIDraw.Add(function()
    if KnoxLink_photoFrame then KnoxLink_photoFrame() end
end)
