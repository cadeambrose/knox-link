--[[
    Knox Link — the scrapbook: photos of the game for the journal.

    A screen (or the "Take a photo" shortcut) asks for a photo; the game's own windows and bars are
    hidden for a moment, like a shutter (the game's "toggle UI"), the server add-on takes the
    screen as the game shows it (Photo.java) and keeps it in the save. It goes on today's journal page (ph = { {id, c = caption, h, mi}, ... }),
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

-- The shutter: the game's windows and bars hidden while a photo is taken, with the game's own "toggle UI".
-- The game draws its UI into a buffer of its own at its own pace, so the photo waits a few frames for it.
local shutter   -- { t = ms, ui = the UI was shown, paused = the "Game paused" line was shown }

local function shutterOpen()
    if shutter then return end
    shutter = { t = getTimestampMs(), ui = ISUIHandler and ISUIHandler.allUIVisible ~= false, paused = UIManager.isShowPausedMessage() }
    if shutter.ui then ISUIHandler.setVisibleAllUI(false) end
    UIManager.setShowPausedMessage(false)
end

local function shutterClose()
    if not shutter then return end
    local s = shutter
    shutter = nil
    if s.ui then ISUIHandler.setVisibleAllUI(true) end
    UIManager.setShowPausedMessage(s.paused)
end

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
    shutterOpen()
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

-- before the game's UI pass: once the UI has been gone long enough, the photo is taken and the UI comes back
-- (whatever happens, after 3 s at most)
Events.OnPreUIDraw.Add(function()
    if not shutter then return end
    local done = not KnoxLink_photoFrame or KnoxLink_photoFrame()
    if done or getTimestampMs() - shutter.t > 3000 then shutterClose() end
end)
