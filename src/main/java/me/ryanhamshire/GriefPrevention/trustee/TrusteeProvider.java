/*
    GriefPrevention Server Plugin for Minecraft
    Trustee provider SPI, added by Sandlot Minecraft in 2026

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention.trustee;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * A source of trustees other than players, {@code public} and {@code [permission]} nodes.
 *
 * <p>A provider owns the trust keys of the form {@code <prefix>:<rest>}. It is registered through Bukkit's
 * {@link org.bukkit.plugin.ServicesManager}, one provider per prefix, and GriefPrevention looks it up at each
 * use. Without a provider for its prefix, a key never matches and the trust commands treat
 * {@code <prefix>:<input>} as they always have.</p>
 *
 * <p>Keys are stored lowercase. The offline {@code hasExplicitPermission(UUID, ...)} check never matches a
 * provider key.</p>
 */
public interface TrusteeProvider
{
    /**
     * @return the key prefix this provider owns, without the colon, for example {@code team}
     */
    String prefix();

    /**
     * Resolve the text a player typed after {@code <prefix>:} to a stored key.
     *
     * @param actor the sender of the trust or untrust command
     * @param input the text after {@code <prefix>:}, possibly empty
     * @return the key {@code <prefix>:<id>}, or empty if the input names nothing
     */
    Optional<String> resolve(CommandSender actor, String input);

    /**
     * Whether a player is one of the trustees a stored key names. Called on the main thread from claim permission
     * checks, so it must answer from memory.
     *
     * @param player the online player being checked
     * @param key the stored key, {@code <prefix>:<id>}
     * @return whether the player is a member
     */
    boolean isMember(Player player, String key);

    /**
     * @param key the stored key, {@code <prefix>:<id>}
     * @return the text to show for the key in {@code /trustlist} and confirmations, or empty if unknown
     */
    Optional<String> display(String key);
}
