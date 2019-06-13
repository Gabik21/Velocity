package com.velocitypowered.proxy.tablist;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.packet.HeaderAndFooter;
import com.velocitypowered.proxy.protocol.packet.PlayerListItem;
import com.velocitypowered.proxy.protocol.packet.PlayerListItem.Item;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;

public class VelocityTabList implements TabList {

  private final MinecraftConnection connection;
  private final Map<UUID, TabListEntry> entries = new ConcurrentHashMap<>();
  // Legacy 1.7
  private final Map<String, PlayerListItem.Item> usernames = new ConcurrentHashMap<>();

  public VelocityTabList(MinecraftConnection connection) {
    this.connection = connection;
  }

  @Override
  public void setHeaderAndFooter(Component header, Component footer) {
    Preconditions.checkNotNull(header, "header");
    Preconditions.checkNotNull(footer, "footer");
    connection.write(HeaderAndFooter.create(header, footer));
  }

  @Override
  public void clearHeaderAndFooter() {
    connection.write(HeaderAndFooter.reset());
  }

  @Override
  public void addEntry(TabListEntry entry) {
    Preconditions.checkNotNull(entry, "entry");
    Preconditions.checkArgument(entry.getTabList().equals(this),
        "The provided entry was not created by this tab list");
    Preconditions.checkArgument(!entries.containsKey(entry.getProfile().getId()),
        "this TabList already contains an entry with the same uuid");

    PlayerListItem.Item packetItem = PlayerListItem.Item.from(entry);
    connection.write(
        new PlayerListItem(PlayerListItem.ADD_PLAYER, Collections.singletonList(packetItem)));
    entries.put(entry.getProfile().getId(), entry);
  }

  @Override
  public Optional<TabListEntry> removeEntry(UUID uuid) {
    TabListEntry entry = entries.remove(uuid);
    if (entry != null) {
      PlayerListItem.Item packetItem = PlayerListItem.Item.from(entry);
      connection.write(
          new PlayerListItem(PlayerListItem.REMOVE_PLAYER, Collections.singletonList(packetItem)));
    }

    return Optional.ofNullable(entry);
  }

  /**
   * Clears all entries from the tab list. Note that the entries are written with {@link
   * MinecraftConnection#delayedWrite(Object)}, so make sure to do an explicit {@link
   * MinecraftConnection#flush()}.
   */
  public void clearAll() {
    List<PlayerListItem.Item> items = new ArrayList<>();
    for (TabListEntry value : entries.values()) {
      items.add(PlayerListItem.Item.from(value));
    }
    entries.clear();
    if (!items.isEmpty()) {
      connection.delayedWrite(new PlayerListItem(PlayerListItem.REMOVE_PLAYER, items));
    } else if (!usernames.isEmpty()) {
      for (Item item : usernames.values()) {
        connection.delayedWrite(
            new PlayerListItem(PlayerListItem.REMOVE_PLAYER, Collections.singletonList(item)));
      }
      usernames.clear();
    }
  }

  @Override
  public Collection<TabListEntry> getEntries() {
    return Collections.unmodifiableCollection(this.entries.values());
  }

  @Override
  public TabListEntry buildEntry(GameProfile profile, @Nullable Component displayName, int latency,
      int gameMode) {
    return new VelocityTabListEntry(this, profile, displayName, latency, gameMode);
  }

  /**
   * Processes a tab list entry packet from the backend.
   *
   * @param packet the packet to process
   */
  public void processBackendPacket(PlayerListItem packet) {
    // Packets are already forwarded on, so no need to do that here
    for (PlayerListItem.Item item : packet.getItems()) {
      UUID uuid = item.getUuid();

      // Legacy 1.7
      if (uuid == null) {
        switch (packet.getAction()) { // only got two cases
          case PlayerListItem.ADD_PLAYER:
            usernames.put(item.getName(), item);
            break;
          case PlayerListItem.REMOVE_PLAYER:
            usernames.remove(item.getName());
            break;
          default:
            break;
        }
        continue;
      }

      if (packet.getAction() != PlayerListItem.ADD_PLAYER && !entries.containsKey(uuid)) {
        // Sometimes UPDATE_GAMEMODE is sent before ADD_PLAYER so don't want to warn here
        continue;
      }

      switch (packet.getAction()) {
        case PlayerListItem.ADD_PLAYER: {
          // ensure that name and properties are available
          String name = item.getName();
          List<GameProfile.Property> properties = item.getProperties();
          if (name == null || properties == null) {
            throw new IllegalStateException("Got null game profile for ADD_PLAYER");
          }
          entries.put(item.getUuid(), TabListEntry.builder()
              .tabList(this)
              .profile(new GameProfile(uuid, name, properties))
              .displayName(item.getDisplayName())
              .latency(item.getLatency())
              .gameMode(item.getGameMode())
              .build());
          break;
        }
        case PlayerListItem.REMOVE_PLAYER:
          entries.remove(uuid);
          break;
        case PlayerListItem.UPDATE_DISPLAY_NAME: {
          TabListEntry entry = entries.get(uuid);
          if (entry != null) {
            entry.setDisplayName(item.getDisplayName());
          }
          break;
        }
        case PlayerListItem.UPDATE_LATENCY: {
          TabListEntry entry = entries.get(uuid);
          if (entry != null) {
            entry.setLatency(item.getLatency());
          }
          break;
        }
        case PlayerListItem.UPDATE_GAMEMODE: {
          TabListEntry entry = entries.get(uuid);
          if (entry != null) {
            entry.setLatency(item.getGameMode());
          }
          break;
        }
        default:
          // Nothing we can do here
          break;
      }
    }
  }

  void updateEntry(int action, TabListEntry entry) {
    if (entries.containsKey(entry.getProfile().getId())) {
      PlayerListItem.Item packetItem = PlayerListItem.Item.from(entry);
      connection.write(new PlayerListItem(action, Collections.singletonList(packetItem)));
    }
  }
}
