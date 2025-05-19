Change Log
--
Configuration Enhancements:
New Options Added: Introduced two new JSON config options:
banOnUnauthorized: When set to true, unauthorized users are added to the server's ban list.
kickOnUnauthorized: When set to true, unauthorized users are kicked from the server.
Config Format Update: Updated the configuration file structure to include these options alongside requireIpValidation and the allowedPlayers list.
Config Fixes: Corrected formatting issues in the config (e.g., removed the trailing colon from the IP address for "WooTiPanTz").

Authorization Logic Updates:
Unauthorized Access Handling: Modified the join event handler so that if a player is not on the allowed list or connects with an unapproved IP:
The mod now calls a new helper method (handleUnauthorized) that checks the config flags and either kicks, bans, or both.
Conditional Punishment: The behavior (kick, ban, or both) is now determined by the new config options, making the security response fully customizable.

Ban System Bug Fix:
Ban Entry Correction: Resolved a type mismatch error by wrapping a player's GameProfile into a proper BannedPlayerEntry before adding it to the ban list.
Previous error: Attempting to add a GameProfile directly.
New implementation: Creating a BannedPlayerEntry with the player's profile, source, and reason.

Logging Improvements:
Enhanced Logging: Added more descriptive logging messages to track:
Unauthorized access attempts.
Punishment actions (kicks and bans) performed on players.

Command Registration:
Various Commands: Included commands like /addUser, /removeUser, /toggleUserIp, /addIp, /removeIp, and /refreshConfig to manage the allowed players list dynamically.
Global IP Validation Toggle: A command (/toggleGlobalIp) was added to enable or disable IP validation server-wide.
--

Swrpgservauth is a Minecraft server authentication mod that restricts player access based on a predefined username allowlist. The mod prevents unauthorized players from joining by checking their username against a configuration file (allowed_players.json). If a player is not listed, they are disconnected upon joining, ensuring tight control over server access.

Administrators can manage the allowlist through commands:

User Management Commands
/addUser <username> Adds a user to the allowed list with no IP restrictions. By default, their verifyIp flag is set to the current global setting.

/removeUser <username> Removes a user entirely from the allowed list.

/listUsers Displays all allowed users along with their verifyIp status and registered IPs.

IP Management Commands
/addIp <username> <ip> Adds an IP address to an existing user’s allowed IP list.

/removeIp <username> <ip> Removes an IP address from a user’s allowed IP list.

/addIPUser <username> <ip> Adds an IP to a user and refreshes the configuration immediately.

IP Verification Settings
/toggleUserIp <username> Toggles a user’s individual verifyIp flag (true/false). When enabled, their IP must match a registered IP.

/toggleGlobalIp Toggles the global IP validation flag (requireIpValidation). When enabled, all users who have verifyIp=true must pass an IP check.

Configuration Management
/refreshConfig Reloads the JSON configuration file, applying any changes made externally.

The mod features automated logging for connection attempts, notifying administrators when unauthorized players try to join. It also ensures players are fully removed from the server if they are disconnected due to authentication failure, preventing ghosting issues.

Single-Sentence Summary:
Swrpgservauth enhances Minecraft server security by enforcing username-based authentication, disconnecting unauthorized players, and providing admin commands for managing access.

Added optional IP verification. - global / or per user.
