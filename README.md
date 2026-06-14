## PearlPlus

PearlPlus automatically detects new stasis pearls and registers them with its own pearl loader. Pearl throwers can then load these pearls through chat whispers.
The config is saved to `plugins/config/pearlplus.json`

This fork lives at [jackforg/PearlPlus](https://github.com/jackforg/PearlPlus).

### Credits

- Original authors: `duccss`, `steve2b2t`, `Leotonic`
- Current fork and ongoing feature work: `jackforg`

In Zenith run `plugins download <release-asset-url>` from this repo's releases or download the [latest build](https://github.com/jackforg/PearlPlus/releases/latest) and place the jar file in your proxy's plugin folder.

This plugin **WILL NOT WORK** unless a correct `chatschema` is set in Zenith. Most vanilla servers like 2b2t and Constantiam don't require you to set one but other servers with custom whisper builders for example 9b9t will need one. Please check the wiki [here](https://wiki.2b2t.vc/Commands/#chatschema).
You might also need to set the whisper command for the server you're playing on using `extraChat whisperCommand <command>` to allow the bot to whisper back.

If you're chat banned/muted you can load pearls in your client using [PearlPlusMod](https://github.com/duccss/PearlPlusMod) and [PearlPlusWebAPI](https://github.com/duccss/PearlPlusWebAPI) which bypass's chat.

### Management Commands

#### You can use either `pp` or `pearlplus`

```bash
pearlplus <on/off>
```
```bash
pearlplus add <playerName> <pearlId> <x> <y> <z>
```
```bash
pearlplus del <playerName> <pearlId>
```
```bash
pearlplus list
```
```bash
pearlplus list clear
```
```bash
pearlplus defaultpearlid <word/none>
```
```bash
pearlplus autodefault <on/off>
```
```bash
pearlplus strict <on/off>
```
```bash
pearlplus loadcommand <word>
```
Sets the in-game whisper trigger used by normal pearl loads. The default is `load`.

```bash
pearlplus autodetect <on/off>
```
```bash
pearlplus autodetect temp <on/off>
```
```bash
pearlplus returnpos <on/off>
```
```bash
pearlplus distancecheck <on/off>
```

```bash
pearlplus whitelist <on/off>
pearlplus whitelist add <playername>
pearlplus whitelist del <playername>
pearlplus whitelist list
pearlplus whitelist clear
```

```bash
pearlplus droppearlafterload <on/off>
```

```bash
pearlplus offlineload <on/off>
pearlplus offlineload channel <channelId|none>
pearlplus offlineload role <roleId|none>
pearlplus offlineload mainchannel <on/off>
pearlplus discordbindings list
pearlplus discordbindings remove <playerName>
pearlplus discordbindings clear
pearlplus discordtrusted add <playerName> <discordUserIdOrUsername>
pearlplus discordtrusted list
pearlplus discordtrusted remove <playerName>
pearlplus discordtrusted clear
```

### In-game Whisper Commands

There are a few in-game commands players can whisper to the bot to manage their pearls.

`pearls` will list all pearlID's with an asterisk next to ID's where a pearl isn't detected.

`rename oldPearlID newPearlID` changes the pearlID.

`default PearlID` sets that pearl as default if `autodefault` disabled.

`bind CODE` links the whispering Minecraft account to a Discord user who requested a link code.

`<loadCommand> optionalPearlID` loads your pearl through whispers. The default load command is `load`, and staff can change it with `pp loadcommand <word>`.

### Discord Offline Load

Players can stage a pearl from Discord while offline, then log in during a 2 minute armed window to trigger it immediately.

1. In Discord, run `pp discord link`
2. The bot DMs you a one-time code
3. While online in game, whisper `bind CODE` to the bot once
4. Later, while offline, run `pp offline load <optionalPearlId>` in Discord
5. The bot paths to the chamber, waits up to 2 minutes, and triggers as soon as it detects you online

Additional Discord commands:

`.pp controls` or `.pp offline controls` posts a user-locked button panel using
the current linked accounts and stored pearls. Refresh rebuilds the panel from
live config, and the load, status, and cancel buttons continue working after a
ZenithProxy restart.

`pp offline status` shows whether a staged offline load is pathing or armed.

`pp offline cancel` cancels your staged offline load.

If your Discord account is trusted for multiple Minecraft accounts, you can target a specific IGN:

`pp offline load <ign>`

or a specific pearl on that IGN:

`pp offline load <ign> <pearlId>`

### Dedicated Crew Discord Channel

PearlPlus can listen in its own Discord channel instead of Zenith's main command channel. This is useful when you want crew members to use only offline pearl commands without getting access to broader Zenith Discord commands.

Recommended setup:

1. Keep your normal Zenith control channel private to staff
2. Create a separate Discord channel such as `#pearl-loads`
3. Set that channel ID in PearlPlus with:

```bash
pp offlineload channel <channelId>
```

4. Optionally require a dedicated crew role for that channel:

```bash
pp offlineload role <roleId>
```

5. If you want PearlPlus offline commands to stay out of Zenith's main command channel entirely:

```bash
pp offlineload mainchannel off
```

Crew can then use commands like `.pp offline load` in the dedicated PearlPlus channel, while Zenith's normal bot commands remain limited to your main control channel.

### Trusted Discord Regulars

If you already know a regular's Discord identity, you can trust them ahead of time so they do not need a one-time bind code.

Use either:

`pp discordtrusted add <playerName> <discordUserIdOrUsername>`

or add entries directly to `plugins/config/pearlplus.json` under `offlineLoad.trustedDiscordBindings`.

Example:

```json
"trustedDiscordBindings": {
  "ExamplePlayer": {
    "playerName": "ExamplePlayer",
    "playerUuid": "00000000-0000-0000-0000-000000000000",
    "discordUserId": "123456789012345678",
    "discordUsername": null
  }
}
```

Using a Discord user ID is recommended. Username matching is supported for convenience, but it is less secure because names and nicknames can change.

### Usage

Simply throw a new ender pearl and once it becomes stable the bot will register it, setting the pearlID as "Base" by default with an incrementing number for subsequent pearls. That player can now whisper the configured load command to the zenith bot and the bot will load the pearl. Players with multiple pearls can add the pearlID after the load command to have a specific pearl loaded. Players will receive a warning whisper when loading a stasis chamber where a pearl isn't detected.
```bash
/w <botName> <loadCommand> <optionalID>
```
By default, when a player doesn't specify which pearl they want loaded the bot will load whatever one where a pearl is detected. Can be disabled with `pp autodefault off`

Temp mode automatically removes pearl positions where a pearl isn't detected. May be buggy. Not recommended. Do **NOT** use `pp distancecheck` with temp mode.

Can be enabled with `pp autodetect temp on` 

#### Manual setup
Use the `pp add/del` commands to set up manually.

#### 2b2t / Anti-spam

By default, the bot resolves the username of pearl throwers with entity ID's. Some servers might not allow this so if the bot is unable to register pearls automatically use `pp distancecheck on`. This will get the throwers name from the closest player to the pearl. 2b2t players have reported autodetect ceasing to work occasionally. Always test before enabling this feature.

PearlPlus now also captures pearl spawn ownership at packet time and can register from the thrower's UUID before their name resolves, which makes the default owner-id path more reliable on laggy servers like 2b2t.

By default, you can add a random word after the load command or the `pearlID` to get around anti-spam. This can be disabled using `pp strict on`.

#### Recommended Zenith settings

`antiAFK walk off`

`b allowBreak off`

`b allowPlace off`

These settings will stop your pearl bot walking off and prevent it breaking/placing blocks as baritone paths to the pearl trapdoor.

### Building The Plugin

Clone the repo or download the zip.
Run `chmod +x gradlew`
 then `./gradlew build`
