package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.trustee.TrusteeProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The Trustee provider SPI: trust commands store lowercase provider keys, permission checks ask the provider at
 * the stored level only (Manage included), /trustlist shows the provider's display text, and with no provider
 * nothing changes.
 */
public class TrusteeProviderTest
{
    private static final String TEAM_ID = "2f3c5d7e-1a2b-4c3d-8e9f-0a1b2c3d4e5f";
    private static final String TEAM_KEY = "team:" + TEAM_ID;

    /** A provider with one team, Snowballs, and an explicit member set. */
    static final class FakeTeams implements TrusteeProvider
    {
        final Set<UUID> members = new HashSet<>();
        boolean throwOnMember = false;
        int isMemberCalls = 0;

        @Override
        public String prefix()
        {
            return "team";
        }

        @Override
        public Optional<String> resolve(CommandSender actor, String input)
        {
            // Mixed case on purpose: GriefPrevention must lowercase what it stores.
            return input.equalsIgnoreCase("Snowballs") ? Optional.of("team:" + TEAM_ID.toUpperCase()) : Optional.empty();
        }

        @Override
        public boolean isMember(Player player, String key)
        {
            isMemberCalls++;
            if (throwOnMember) throw new IllegalStateException("provider fault");
            return key.equals(TEAM_KEY) && members.contains(player.getUniqueId());
        }

        @Override
        public Optional<String> display(String key)
        {
            return key.equals(TEAM_KEY) ? Optional.of("team:Snowballs") : Optional.empty();
        }
    }

    private MockedStatic<Bukkit> bukkit;
    private final List<RegisteredServiceProvider<TrusteeProvider>> registrations = new ArrayList<>();
    private final FakeTeams teams = new FakeTeams();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private Player owner;
    private Player member;
    private GriefPrevention plugin;
    private DataStore dataStore;
    private final Map<Messages, String> lastMessage = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception
    {
        ServicesManager services = mock(ServicesManager.class);
        when(services.getRegistrations(TrusteeProvider.class)).thenReturn(registrations);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

        owner = player(ownerId);
        member = player(memberId);

        // A plugin instance with real command code, a mocked data store and no server.
        plugin = mock(GriefPrevention.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        dataStore = mock(DataStore.class);
        when(dataStore.getMessage(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
            Messages id = inv.getArgument(0);
            Object[] raw = inv.getRawArguments();
            String text = id.name() + ":" + String.join("|", (String[]) raw[1]);
            lastMessage.put(id, text);
            return text;
        });
        plugin.dataStore = dataStore;
        plugin.playerNameToIDMap = new ConcurrentHashMap<>();
        doReturn(null).when(plugin).resolvePlayerByName(anyString());
        GriefPrevention.instance = plugin;
        Field log = GriefPrevention.class.getDeclaredField("log");
        log.setAccessible(true);
        log.set(null, Logger.getLogger("GriefPreventionTest"));
    }

    @AfterEach
    void tearDown()
    {
        bukkit.close();
        GriefPrevention.instance = null;
    }

    private static Player player(UUID id)
    {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("p" + id.toString().substring(0, 4));
        return player;
    }

    private void register()
    {
        registrations.add(new RegisteredServiceProvider<>(TrusteeProvider.class, teams, ServicePriority.Normal, mock(Plugin.class)));
    }

    private Claim claim(List<String> builders, List<String> containers, List<String> accessors, List<String> managers)
    {
        return new Claim(new Location(null, 0, 0, 0), new Location(null, 9, 64, 9), ownerId,
                builders, containers, accessors, managers, 1L);
    }

    private Claim emptyClaim()
    {
        return claim(List.of(), List.of(), List.of(), List.of());
    }

    /** Every key in the claim's level map (builders, containers, accessors), not managers. */
    private static List<String> levelKeys(Claim claim)
    {
        ArrayList<String> builders = new ArrayList<>(), containers = new ArrayList<>(), accessors = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, new ArrayList<>());
        List<String> keys = new ArrayList<>(builders);
        keys.addAll(containers);
        keys.addAll(accessors);
        return keys;
    }

    private void standIn(Claim claim)
    {
        when(dataStore.getClaimAt(any(), anyBoolean(), any())).thenReturn(claim);
    }

    private boolean command(String name, String argument)
    {
        Command cmd = mock(Command.class);
        when(cmd.getName()).thenReturn(name);
        return plugin.onCommand(owner, cmd, name, new String[] { argument });
    }

    // ---- resolve stores lowercase team:<id> ----

    @Test
    void trustStoresLowercaseKeyAtEachLevel()
    {
        register();
        Claim claim = emptyClaim();
        standIn(claim);

        assertTrue(command("accesstrust", "team:Snowballs"));
        assertEquals(ClaimPermission.Access, claim.getPermission(TEAM_KEY));
        assertTrue(command("containertrust", "team:snowballs"));
        assertEquals(ClaimPermission.Container, claim.getPermission(TEAM_KEY));
        assertTrue(command("trust", "team:SNOWBALLS"));
        assertEquals(ClaimPermission.Build, claim.getPermission(TEAM_KEY));
        assertEquals(List.of(TEAM_KEY), levelKeys(claim));
    }

    @Test
    void permissionTrustStoresLowercaseKeyOnManagePath()
    {
        register();
        Claim claim = emptyClaim();
        standIn(claim);

        assertTrue(command("permissiontrust", "team:Snowballs"));
        assertEquals(List.of(TEAM_KEY), claim.managers);
        assertTrue(levelKeys(claim).isEmpty());
    }

    @Test
    void trustConfirmationNamesDisplay()
    {
        register();
        standIn(emptyClaim());

        command("trust", "team:Snowballs");
        assertTrue(lastMessage.get(Messages.GrantPermissionConfirmation).startsWith("GrantPermissionConfirmation:team:Snowballs|"),
                lastMessage.get(Messages.GrantPermissionConfirmation));
    }

    @Test
    void unknownTeamIsRefusedAndNothingStored()
    {
        register();
        Claim claim = emptyClaim();
        standIn(claim);

        assertTrue(command("trust", "team:Nope"));
        assertTrue(command("permissiontrust", "team:"));
        verify(owner).sendMessage(TextMode.Err + "Nope is not a team.");
        verify(owner).sendMessage(TextMode.Err + "team: is not a team.");
        assertTrue(levelKeys(claim).isEmpty());
        assertTrue(claim.managers.isEmpty());
    }

    @Test
    void untrustResolvesAndDropsKeyFromBothLists()
    {
        register();
        Claim claim = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of(TEAM_KEY));
        standIn(claim);

        assertTrue(command("untrust", "team:SNOWBALLS"));
        assertNull(claim.getPermission(TEAM_KEY));
        assertTrue(claim.managers.isEmpty());
        assertEquals("UntrustIndividualSingleClaim:team:Snowballs", lastMessage.get(Messages.UntrustIndividualSingleClaim));
    }

    // ---- isMember grants at the stored level and not above, including the Manage branch ----

    @Test
    void memberGetsStoredLevelAndNotAbove()
    {
        register();
        teams.members.add(memberId);

        Claim access = claim(List.of(), List.of(), List.of(TEAM_KEY), List.of());
        assertTrue(access.hasExplicitPermission(member, ClaimPermission.Access));
        assertFalse(access.hasExplicitPermission(member, ClaimPermission.Container));
        assertFalse(access.hasExplicitPermission(member, ClaimPermission.Build));
        assertFalse(access.hasExplicitPermission(member, ClaimPermission.Manage));
        assertFalse(access.hasExplicitPermission(member, ClaimPermission.Edit));

        Claim container = claim(List.of(), List.of(TEAM_KEY), List.of(), List.of());
        assertTrue(container.hasExplicitPermission(member, ClaimPermission.Access));
        assertTrue(container.hasExplicitPermission(member, ClaimPermission.Container));
        assertFalse(container.hasExplicitPermission(member, ClaimPermission.Build));
        assertFalse(container.hasExplicitPermission(member, ClaimPermission.Manage));

        Claim build = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of());
        assertTrue(build.hasExplicitPermission(member, ClaimPermission.Access));
        assertTrue(build.hasExplicitPermission(member, ClaimPermission.Container));
        assertTrue(build.hasExplicitPermission(member, ClaimPermission.Build));
        assertFalse(build.hasExplicitPermission(member, ClaimPermission.Manage));
        assertFalse(build.hasExplicitPermission(member, ClaimPermission.Edit));
    }

    @Test
    void manageBranchAsksProvider()
    {
        register();
        teams.members.add(memberId);

        Claim manage = claim(List.of(), List.of(), List.of(), List.of(TEAM_KEY));
        assertTrue(manage.hasExplicitPermission(member, ClaimPermission.Manage));
        // Manage is a separate list: it grants no build trust, as for players and [nodes].
        assertFalse(manage.hasExplicitPermission(member, ClaimPermission.Build));
        assertFalse(manage.hasExplicitPermission(member, ClaimPermission.Edit));
    }

    @Test
    void nonMemberIsRefusedAtEveryLevel()
    {
        register();
        Player outsider = player(UUID.randomUUID());
        Claim claim = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of(TEAM_KEY));
        for (ClaimPermission level : ClaimPermission.values())
        {
            assertFalse(claim.hasExplicitPermission(outsider, level), level.name());
        }
    }

    @Test
    void offlineCheckNeverMatchesProviderKey()
    {
        register();
        teams.members.add(memberId);
        Claim claim = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of(TEAM_KEY));
        assertFalse(claim.hasExplicitPermission(memberId, ClaimPermission.Build));
        assertFalse(claim.hasExplicitPermission(memberId, ClaimPermission.Manage));
        assertEquals(0, teams.isMemberCalls);
    }

    @Test
    void providerFaultDenies()
    {
        register();
        teams.members.add(memberId);
        teams.throwOnMember = true;
        Claim claim = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of(TEAM_KEY));
        assertFalse(claim.hasExplicitPermission(member, ClaimPermission.Build));
        assertFalse(claim.hasExplicitPermission(member, ClaimPermission.Manage));
    }

    @Test
    void providerIsLookedUpAtEachUse()
    {
        teams.members.add(memberId);
        Claim claim = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of());
        assertFalse(claim.hasExplicitPermission(member, ClaimPermission.Build));
        register(); // e.g. the provider's plugin enabling after GriefPrevention
        assertTrue(claim.hasExplicitPermission(member, ClaimPermission.Build));
        registrations.clear();
        assertFalse(claim.hasExplicitPermission(member, ClaimPermission.Build));
    }

    // ---- display is used by trustEntryToPlayerName ----

    @Test
    void trustListUsesDisplay()
    {
        register();
        assertEquals("team:Snowballs", GriefPrevention.trustEntryToPlayerName(TEAM_KEY));
        assertEquals("public", GriefPrevention.trustEntryToPlayerName("public"));
        assertEquals("[some.node]", GriefPrevention.trustEntryToPlayerName("[some.node]"));
    }

    // ---- with no provider, nothing changes ----

    @Test
    void noProviderTeamKeyIsNotAMember()
    {
        Claim claim = claim(List.of(TEAM_KEY), List.of(), List.of(), List.of(TEAM_KEY));
        for (ClaimPermission level : ClaimPermission.values())
        {
            assertFalse(claim.hasExplicitPermission(member, level), level.name());
        }
        verify(member, never()).hasPermission(anyString());
    }

    @Test
    void noProviderTeamKeyShowsAsSomeone()
    {
        assertEquals("someone", GriefPrevention.trustEntryToPlayerName(TEAM_KEY));
    }

    @Test
    void noProviderTrustCommandIsStock()
    {
        Claim claim = emptyClaim();
        standIn(claim);
        assertTrue(command("trust", "team:Snowballs"));
        // Stock: no player by that name, and no '.', so PlayerNotFound2 and nothing stored.
        assertEquals("PlayerNotFound2:", lastMessage.get(Messages.PlayerNotFound2));
        assertTrue(levelKeys(claim).isEmpty());
    }

    @Test
    void playerPublicAndNodeBehaviourUnchanged()
    {
        // Run once without and once with a provider: the answers must be identical.
        for (int pass = 0; pass < 2; pass++)
        {
            if (pass == 1) register();
            Player nodeHolder = player(UUID.randomUUID());
            when(nodeHolder.hasPermission("some.node")).thenReturn(true);
            when(nodeHolder.hasPermission("manage.node")).thenReturn(true);
            Player stranger = player(UUID.randomUUID());

            Claim claim = claim(List.of(memberId.toString(), "[some.node]"), List.of(), List.of("public"),
                    List.of("[manage.node]"));

            // player key
            assertTrue(claim.hasExplicitPermission(member, ClaimPermission.Build));
            assertFalse(claim.hasExplicitPermission(member, ClaimPermission.Manage));
            assertTrue(claim.hasExplicitPermission(memberId, ClaimPermission.Build));
            // [node] keys, level and Manage branches
            assertTrue(claim.hasExplicitPermission(nodeHolder, ClaimPermission.Build));
            assertTrue(claim.hasExplicitPermission(nodeHolder, ClaimPermission.Manage));
            assertFalse(claim.hasExplicitPermission(nodeHolder, ClaimPermission.Edit));
            // public is not an explicit permission; it is read by checkPermission's public rule
            assertFalse(claim.hasExplicitPermission(stranger, ClaimPermission.Access));
            assertEquals(ClaimPermission.Access, claim.getPermission("public"));
            // owner
            assertTrue(claim.hasExplicitPermission(owner, ClaimPermission.Manage));
        }
        assertEquals(0, teams.isMemberCalls);
    }
}
