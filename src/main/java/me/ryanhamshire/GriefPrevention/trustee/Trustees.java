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

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GriefPrevention's side of the {@link TrusteeProvider} SPI. Providers are looked up through the
 * {@link org.bukkit.plugin.ServicesManager} at each use and never cached, so a provider registered after
 * GriefPrevention enables is seen, and one that unregisters stops matching.
 */
public final class Trustees
{
    private static final Logger LOG = Logger.getLogger("GriefPrevention");

    private Trustees() {}

    /**
     * @param entry a stored key or a command argument
     * @return the registered provider for the text before the first colon, or null if there is none
     */
    public static @Nullable TrusteeProvider providerFor(@NotNull String entry)
    {
        int colon = entry.indexOf(':');
        if (colon <= 0) return null;
        String prefix = entry.substring(0, colon);
        for (RegisteredServiceProvider<TrusteeProvider> registration :
                Bukkit.getServicesManager().getRegistrations(TrusteeProvider.class))
        {
            TrusteeProvider provider = registration.getProvider();
            if (prefix.equalsIgnoreCase(provider.prefix())) return provider;
        }
        return null;
    }

    /**
     * Resolve a {@code <prefix>:<input>} command argument to the key to store.
     *
     * @return the key, lowercase and under the provider's prefix, or null if the input names nothing
     */
    public static @Nullable String resolve(@NotNull TrusteeProvider provider, @NotNull CommandSender actor, @NotNull String argument)
    {
        String input = input(argument);
        String key = provider.resolve(actor, input).orElse(null);
        if (key == null) return null;
        key = key.toLowerCase(Locale.ROOT);
        // A key outside the provider's prefix would never reach it again: refuse it.
        if (!key.startsWith(provider.prefix().toLowerCase(Locale.ROOT) + ":")) return null;
        return key;
    }

    /**
     * @return the text after the first colon of a {@code <prefix>:<input>} argument
     */
    public static @NotNull String input(@NotNull String argument)
    {
        return argument.substring(argument.indexOf(':') + 1);
    }

    /**
     * Whether a player is a member of the trustees a stored key names. False for a key with no registered
     * provider, and false if the provider throws, so a provider fault never grants access.
     */
    public static boolean isMember(@NotNull Player player, @NotNull String key)
    {
        TrusteeProvider provider = providerFor(key);
        if (provider == null) return false;
        try
        {
            return provider.isMember(player, key);
        }
        catch (RuntimeException e)
        {
            LOG.log(Level.WARNING, "Trustee provider '" + provider.prefix() + "' failed on " + key + "; denying", e);
            return false;
        }
    }

    /**
     * @return the provider's display text for a stored key, or null if no provider owns it or it has none
     */
    public static @Nullable String display(@NotNull String key)
    {
        TrusteeProvider provider = providerFor(key);
        if (provider == null) return null;
        return provider.display(key).orElse(null);
    }
}
