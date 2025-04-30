Swrpgservauth is a Minecraft server authentication mod that restricts player access based on a predefined username allowlist. The mod prevents unauthorized players from joining by checking their username against a configuration file (allowed_players.json). If a player is not listed, they are disconnected upon joining, ensuring tight control over server access.

Administrators can manage the allowlist through commands:

/addUser <username> — Adds a player to the allowlist and saves the updated configuration.

/removeUser <username> — Removes a player from the allowlist, preventing future logins.

/listUsers — Displays all authorized players currently in the configuration.

/refreshConfig — Reloads the allowlist from the configuration file.

The mod features automated logging for connection attempts, notifying administrators when unauthorized players try to join. It also ensures players are fully removed from the server if they are disconnected due to authentication failure, preventing ghosting issues.

Single-Sentence Summary:
Swrpgservauth enhances Minecraft server security by enforcing username-based authentication, disconnecting unauthorized players, and providing admin commands for managing access.

Added optional IP verification. - global / or per user.
