package me.neznamy.tab.platforms.bukkit.v1_21_R5;

import lombok.NonNull;
import lombok.SneakyThrows;
import me.neznamy.tab.platforms.bukkit.BukkitTabPlayer;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.platform.decorators.SafeScoreboard;
import net.minecraft.EnumChatFormat;
import net.minecraft.network.chat.IChatBaseComponent;
import net.minecraft.network.chat.IChatMutableComponent;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.PacketPlayOutScoreboardTeam.a;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.ScoreboardObjective;
import net.minecraft.world.scores.ScoreboardTeam;
import net.minecraft.world.scores.ScoreboardTeamBase;
import net.minecraft.world.scores.criteria.IScoreboardCriteria;
import org.bukkit.craftbukkit.v1_21_R5.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scoreboard implementation using direct NMS code.
 *
 * <p>Modified to remove the forced space between fancyValue and the belowname
 * objective title. Minecraft's client always renders:
 *   [numberFormat] + SPACE + [objective display name]
 *
 * <p>To work around this, we keep the objective's display name empty on the
 * wire and append the original title text directly to each score's FixedFormat,
 * concatenating them with no separator.
 */
public class NMSPacketScoreboard extends SafeScoreboard<BukkitTabPlayer> {

    private static final ScoreboardTeamBase.EnumNameTagVisibility[] visibilities = ScoreboardTeamBase.EnumNameTagVisibility.values();
    private static final ScoreboardTeamBase.EnumTeamPush[] collisions = ScoreboardTeamBase.EnumTeamPush.values();
    private static final Scoreboard dummyScoreboard = new Scoreboard();

    private static final Constructor<PacketPlayOutScoreboardTeam> teamConstructor;

    static {
        try {
            teamConstructor = PacketPlayOutScoreboardTeam.class.getDeclaredConstructor(String.class, int.class, Optional.class, Collection.class);
            teamConstructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Stores the real (original) title for each objective by name so we can
     * append it to every score's FixedFormat instead of sending it as the
     * objective display name (which would introduce the unwanted space).
     */
    private final Map<String, IChatBaseComponent> objectiveTitles = new ConcurrentHashMap<>();

    /**
     * Constructs new instance with given player.
     *
     * @param   player
     *          Player this scoreboard will belong to
     */
    public NMSPacketScoreboard(@NotNull BukkitTabPlayer player) {
        super(player);
    }

    @Override
    public void registerObjective(@NonNull Objective objective) {
        // Convert and store the real title so setScore can append it later.
        IChatBaseComponent realTitle = objective.getTitle().convert();
        objectiveTitles.put(objective.getName(), realTitle);

        // Build the NMS objective with an EMPTY display name so the client
        // never renders the " title" suffix — that space comes from the client
        // rendering: [numberFormat] + SPACE + [objectiveDisplayName].
        ScoreboardObjective obj = new ScoreboardObjective(
                dummyScoreboard,
                objective.getName(),
                IScoreboardCriteria.c,
                IChatBaseComponent.b(""),   // empty title on the wire
                IScoreboardCriteria.EnumScoreboardHealthDisplay.values()[objective.getHealthDisplay().ordinal()],
                false,
                objective.getNumberFormat() == null ? null : objective.getNumberFormat().toFixedFormat(FixedFormat::new)
        );
        objective.setPlatformObjective(obj);
        sendPacket(new PacketPlayOutScoreboardObjective(obj, ObjectiveAction.REGISTER));
    }

    @Override
    public void setDisplaySlot(@NonNull Objective objective) {
        sendPacket(new PacketPlayOutScoreboardDisplayObjective(
                net.minecraft.world.scores.DisplaySlot.values()[objective.getDisplaySlot().ordinal()],
                (ScoreboardObjective) objective.getPlatformObjective()
        ));
    }

    @Override
    public void unregisterObjective(@NonNull Objective objective) {
        objectiveTitles.remove(objective.getName());
        sendPacket(new PacketPlayOutScoreboardObjective((ScoreboardObjective) objective.getPlatformObjective(), ObjectiveAction.UNREGISTER));
    }

    @Override
    public void updateObjective(@NonNull Objective objective) {
        // Keep the stored title in sync when an update arrives.
        IChatBaseComponent realTitle = objective.getTitle().convert();
        objectiveTitles.put(objective.getName(), realTitle);

        ScoreboardObjective obj = (ScoreboardObjective) objective.getPlatformObjective();
        obj.a(IChatBaseComponent.b("")); // keep display name empty
        obj.a(IScoreboardCriteria.EnumScoreboardHealthDisplay.valueOf(objective.getHealthDisplay().name()));
        sendPacket(new PacketPlayOutScoreboardObjective(obj, ObjectiveAction.UPDATE));
    }

    @Override
    public void setScore(@NonNull Score score) {
        // Retrieve the original title for this objective (may be null for
        // non-belowname objectives — falls back to normal behaviour).
        IChatBaseComponent title = objectiveTitles.get(score.getObjective().getName());

        // Build the FixedFormat that will be sent as the score's NumberFormat.
        // If a fancyValue (numberFormat) is set, append the title directly to
        // it — no space. Otherwise just use the title alone (or null).
        FixedFormat numberFormat = buildNumberFormat(score, title);

        sendPacket(new PacketPlayOutScoreboardScore(
                score.getHolder(),
                score.getObjective().getName(),
                score.getValue(),
                Optional.ofNullable(score.getDisplayName() == null ? null : score.getDisplayName().convert()),
                Optional.ofNullable(numberFormat)
        ));
    }

    /**
     * Builds a {@link FixedFormat} that concatenates fancyValue + title with
     * no separator, effectively replacing the default "value SPACE title"
     * rendering of the Minecraft client.
     *
     * @param score  the score being sent
     * @param title  the stored real objective title (may be {@code null})
     * @return       the composed FixedFormat, or {@code null} if nothing to set
     */
    private FixedFormat buildNumberFormat(@NonNull Score score, IChatBaseComponent title) {
        // Convert the TabComponent directly to IChatBaseComponent (the same cast
        // that NMSComponentConverter does everywhere else in this class).
        IChatBaseComponent fancyComponent = score.getNumberFormat() == null
                ? null
                : score.getNumberFormat().convert();

        if (fancyComponent == null && title == null) {
            return null;
        }

        if (fancyComponent == null) {
            // No fancyValue configured — just show the title with no number prefix.
            return new FixedFormat(title);
        }

        if (title == null || title.getString().isEmpty()) {
            // No title (or empty): keep fancyValue as-is.
            return new FixedFormat(fancyComponent);
        }

        // Concatenate fancyValue + title using a sibling component (no separator).
        // IChatBaseComponent.b("") creates a mutable empty text component.
        IChatMutableComponent combined = (IChatMutableComponent) IChatBaseComponent.b("");
        combined.b(fancyComponent);
        combined.b(title);
        return new FixedFormat(combined);
    }

    @Override
    public void removeScore(@NonNull Score score) {
        sendPacket(new ClientboundResetScorePacket(score.getHolder(), score.getObjective().getName()));
    }

    @Override
    @NotNull
    public Object createTeam(@NonNull String name) {
        return new ScoreboardTeam(dummyScoreboard, name);
    }

    @Override
    public void registerTeam(@NonNull Team team) {
        updateTeamProperties(team);
        ScoreboardTeam t = (ScoreboardTeam) team.getPlatformTeam();
        t.h().addAll(team.getPlayers());
        sendPacket(PacketPlayOutScoreboardTeam.a(t, true));
    }

    @Override
    public void unregisterTeam(@NonNull Team team) {
        sendPacket(PacketPlayOutScoreboardTeam.a((ScoreboardTeam) team.getPlatformTeam()));
    }

    @Override
    public void updateTeam(@NonNull Team team) {
        updateTeamProperties(team);
        sendPacket(PacketPlayOutScoreboardTeam.a((ScoreboardTeam) team.getPlatformTeam(), false));
    }

    private void updateTeamProperties(@NonNull Team team) {
        ScoreboardTeam t = (ScoreboardTeam) team.getPlatformTeam();
        t.a((team.getOptions() & 0x01) != 0);
        t.b((team.getOptions() & 0x02) != 0);
        t.a(visibilities[team.getVisibility().ordinal()]);
        t.a(collisions[team.getCollision().ordinal()]);
        t.b((IChatBaseComponent) team.getPrefix().convert());
        t.c(team.getSuffix().convert());
        t.a(EnumChatFormat.valueOf(team.getColor().name()));
    }

    @Override
    @SneakyThrows
    @NotNull
    public Object onPacketSend(@NonNull Object packet) {
        if (packet instanceof PacketPlayOutScoreboardDisplayObjective display) {
            TAB.getInstance().getFeatureManager().onDisplayObjective(player, display.b().ordinal(), display.e());
        }
        if (packet instanceof PacketPlayOutScoreboardObjective objective) {
            TAB.getInstance().getFeatureManager().onObjective(player, objective.f(), objective.b());
        }
        if (packet instanceof PacketPlayOutScoreboardTeam team) {
            int action = getMethod(team);
            if (action != TeamAction.UPDATE) {
                Collection<String> players = team.g();
                if (players != null) {
                    String name = team.f();
                    return teamConstructor.newInstance(
                            name,
                            action,
                            team.h(),
                            onTeamPacket(action, name, players)
                    );
                }
            }
        }
        return packet;
    }

    private int getMethod(@NonNull PacketPlayOutScoreboardTeam team) {
        if (team.e() == a.a) {
            return 0;
        } else if (team.e() == a.b) {
            return 1;
        } else if (team.b() == a.a) {
            return 3;
        } else if (team.b() == a.b) {
            return 4;
        } else {
            return 2;
        }
    }

    /**
     * Sends the packet to the player.
     *
     * @param   packet
     *          Packet to send
     */
    private void sendPacket(@NotNull Packet<?> packet) {
        ((CraftPlayer)player.getPlayer()).getHandle().g.b(packet);
    }
}
