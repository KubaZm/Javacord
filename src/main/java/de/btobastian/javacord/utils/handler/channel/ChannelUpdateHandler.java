package de.btobastian.javacord.utils.handler.channel;

import com.fasterxml.jackson.databind.JsonNode;
import de.btobastian.javacord.DiscordApi;
import de.btobastian.javacord.entities.DiscordEntity;
import de.btobastian.javacord.entities.User;
import de.btobastian.javacord.entities.channels.ChannelCategory;
import de.btobastian.javacord.entities.channels.ServerChannel;
import de.btobastian.javacord.entities.channels.impl.ImplChannelCategory;
import de.btobastian.javacord.entities.channels.impl.ImplGroupChannel;
import de.btobastian.javacord.entities.channels.impl.ImplServerTextChannel;
import de.btobastian.javacord.entities.channels.impl.ImplServerVoiceChannel;
import de.btobastian.javacord.entities.permissions.Permissions;
import de.btobastian.javacord.entities.permissions.Role;
import de.btobastian.javacord.entities.permissions.impl.ImplPermissions;
import de.btobastian.javacord.events.group.channel.GroupChannelChangeNameEvent;
import de.btobastian.javacord.events.server.channel.ServerChannelChangeCategoryEvent;
import de.btobastian.javacord.events.server.channel.ServerChannelChangeNameEvent;
import de.btobastian.javacord.events.server.channel.ServerChannelChangeNsfwFlagEvent;
import de.btobastian.javacord.events.server.channel.ServerChannelChangeOverwrittenPermissionsEvent;
import de.btobastian.javacord.events.server.channel.ServerChannelChangePositionEvent;
import de.btobastian.javacord.events.server.channel.ServerTextChannelChangeTopicEvent;
import de.btobastian.javacord.events.server.channel.ServerVoiceChannelChangeBitrateEvent;
import de.btobastian.javacord.events.server.channel.ServerVoiceChannelChangeUserLimitEvent;
import de.btobastian.javacord.listeners.group.channel.GroupChannelChangeNameListener;
import de.btobastian.javacord.listeners.server.channel.ServerChannelChangeCategoryListener;
import de.btobastian.javacord.listeners.server.channel.ServerChannelChangeNameListener;
import de.btobastian.javacord.listeners.server.channel.ServerChannelChangeNsfwFlagListener;
import de.btobastian.javacord.listeners.server.channel.ServerChannelChangeOverwrittenPermissionsListener;
import de.btobastian.javacord.listeners.server.channel.ServerChannelChangePositionListener;
import de.btobastian.javacord.listeners.server.channel.ServerTextChannelChangeTopicListener;
import de.btobastian.javacord.listeners.server.channel.ServerVoiceChannelChangeBitrateListener;
import de.btobastian.javacord.listeners.server.channel.ServerVoiceChannelChangeUserLimitListener;
import de.btobastian.javacord.utils.PacketHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the channel update packet.
 */
public class ChannelUpdateHandler extends PacketHandler {

    /**
     * Creates a new instance of this class.
     *
     * @param api The api.
     */
    public ChannelUpdateHandler(DiscordApi api) {
        super(api, true, "CHANNEL_UPDATE");
    }

    @Override
    public void handle(JsonNode packet) {
        int type = packet.get("type").asInt();
        switch (type) {
            case 0:
                handleServerChannel(packet);
                handleServerTextChannel(packet);
                break;
            case 1:
                handlePrivateChannel(packet);
                break;
            case 2:
                handleServerChannel(packet);
                handleServerVoiceChannel(packet);
                break;
            case 3:
                handleGroupChannel(packet);
                break;
            case 4:
                handleServerChannel(packet);
                handleChannelCategory(packet);
                break;
        }
    }

    /**
     * Handles a server channel update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleServerChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getServerChannelById(channelId).ifPresent(channel -> {
            String oldName = channel.getName();
            String newName = jsonChannel.get("name").asText();
            if (!Objects.deepEquals(oldName, newName)) {
                channel.asChannelCategory().ifPresent(cc -> ((ImplChannelCategory) cc).setName(newName));
                channel.asServerTextChannel().ifPresent(stc -> ((ImplServerTextChannel) stc).setName(newName));
                channel.asServerVoiceChannel().ifPresent(svc -> ((ImplServerVoiceChannel) svc).setName(newName));
                ServerChannelChangeNameEvent event =
                        new ServerChannelChangeNameEvent(channel, newName, oldName);

                List<ServerChannelChangeNameListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangeNameListeners());
                listeners.addAll(channel.getServer().getServerChannelChangeNameListeners());
                listeners.addAll(api.getServerChannelChangeNameListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangeName(event));
            }

            int oldPosition = channel.getRawPosition();
            int newPosition = jsonChannel.get("position").asInt();
            if (oldPosition != newPosition) {
                channel.asChannelCategory().ifPresent(cc -> ((ImplChannelCategory) cc).setPosition(newPosition));
                channel.asServerTextChannel().ifPresent(stc -> ((ImplServerTextChannel) stc).setPosition(newPosition));
                channel.asServerVoiceChannel().ifPresent(svc -> ((ImplServerVoiceChannel) svc).setPosition(newPosition));

                ServerChannelChangePositionEvent event =
                        new ServerChannelChangePositionEvent(channel, newPosition, oldPosition);

                List<ServerChannelChangePositionListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangePositionListeners());
                listeners.addAll(channel.getServer().getServerChannelChangePositionListeners());
                listeners.addAll(api.getServerChannelChangePositionListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangePosition(event));
            }

            Collection<Long> rolesWithOverwrittenPermissions = new HashSet<>();
            Collection<Long> usersWithOverwrittenPermissions = new HashSet<>();
            if (jsonChannel.has("permission_overwrites") && !jsonChannel.get("permission_overwrites").isNull()) {
                for (JsonNode permissionOverwriteJson : jsonChannel.get("permission_overwrites")) {
                    Permissions oldOverwrittenPermissions = null;
                    DiscordEntity entity = null;
                    ConcurrentHashMap<Long, Permissions> overwrittenPermissions = null;
                    switch (permissionOverwriteJson.get("type").asText()) {
                        case "role":
                            entity = api.getRoleById(permissionOverwriteJson.get("id").asText()).orElseThrow(() ->
                                    new IllegalStateException("Received channel update event with unknown role!"));
                            oldOverwrittenPermissions = channel.getOverwrittenPermissions((Role) entity);
                            if (channel instanceof ImplChannelCategory) {
                                overwrittenPermissions = ((ImplChannelCategory) channel).getOverwrittenRolePermissions();
                            } else if (channel instanceof ImplServerTextChannel) {
                                overwrittenPermissions = ((ImplServerTextChannel) channel).getOverwrittenRolePermissions();
                            } else if (channel instanceof ImplServerVoiceChannel) {
                                overwrittenPermissions = ((ImplServerVoiceChannel) channel).getOverwrittenRolePermissions();
                            }
                            rolesWithOverwrittenPermissions.add(entity.getId());
                            break;
                        case "member":
                            entity = api.getCachedUserById(permissionOverwriteJson.get("id").asText()).orElseThrow(() ->
                                    new IllegalStateException("Received channel update event with unknown user!"));
                            oldOverwrittenPermissions = channel.getOverwrittenPermissions((User) entity);
                            if (channel instanceof ImplChannelCategory) {
                                overwrittenPermissions = ((ImplChannelCategory) channel).getOverwrittenUserPermissions();
                            } else if (channel instanceof ImplServerTextChannel) {
                                overwrittenPermissions = ((ImplServerTextChannel) channel).getOverwrittenUserPermissions();
                            } else if (channel instanceof ImplServerVoiceChannel) {
                                overwrittenPermissions = ((ImplServerVoiceChannel) channel).getOverwrittenUserPermissions();
                            }
                            usersWithOverwrittenPermissions.add(entity.getId());
                            break;
                        default:
                            throw new IllegalStateException(
                                    "Permission overwrite object with unknown type: " + permissionOverwriteJson.toString());
                    }
                    int allow = permissionOverwriteJson.get("allow").asInt(0);
                    int deny = permissionOverwriteJson.get("deny").asInt(0);
                    Permissions newOverwrittenPermissions = new ImplPermissions(allow, deny);
                    if (!newOverwrittenPermissions.equals(oldOverwrittenPermissions)) {
                        overwrittenPermissions.put(entity.getId(), newOverwrittenPermissions);
                        dispatchServerChannelChangeOverwrittenPermissionsEvent(
                                channel, newOverwrittenPermissions, oldOverwrittenPermissions, entity);
                    }
                }
            }
            ConcurrentHashMap<Long, Permissions> overwrittenRolePermissions = null;
            ConcurrentHashMap<Long, Permissions> overwrittenUserPermissions = null;
            if (channel instanceof ImplChannelCategory) {
                overwrittenRolePermissions = ((ImplChannelCategory) channel).getOverwrittenRolePermissions();
                overwrittenUserPermissions = ((ImplChannelCategory) channel).getOverwrittenUserPermissions();
            } else if (channel instanceof ImplServerTextChannel) {
                overwrittenRolePermissions = ((ImplServerTextChannel) channel).getOverwrittenRolePermissions();
                overwrittenUserPermissions = ((ImplServerTextChannel) channel).getOverwrittenUserPermissions();
            } else if (channel instanceof ImplServerVoiceChannel) {
                overwrittenRolePermissions = ((ImplServerVoiceChannel) channel).getOverwrittenRolePermissions();
                overwrittenUserPermissions = ((ImplServerVoiceChannel) channel).getOverwrittenUserPermissions();
            }

            Iterator<Map.Entry<Long, Permissions>> userIt = overwrittenUserPermissions.entrySet().iterator();
            while (userIt.hasNext()) {
                Map.Entry<Long, Permissions> entry = userIt.next();
                if (usersWithOverwrittenPermissions.contains(entry.getKey())) {
                    continue;
                }
                api.getCachedUserById(entry.getKey()).ifPresent(user -> {
                    Permissions oldPermissions = entry.getValue();
                    userIt.remove();
                    dispatchServerChannelChangeOverwrittenPermissionsEvent(
                            channel, ImplPermissions.EMPTY_PERMISSIONS, oldPermissions, user);
                });
            }

            Iterator<Map.Entry<Long, Permissions>> roleIt = overwrittenRolePermissions.entrySet().iterator();
            while (roleIt.hasNext()) {
                Map.Entry<Long, Permissions> entry = roleIt.next();
                if (rolesWithOverwrittenPermissions.contains(entry.getKey())) {
                    continue;
                }
                api.getRoleById(entry.getKey()).ifPresent(role -> {
                    Permissions oldPermissions = entry.getValue();
                    roleIt.remove();
                    dispatchServerChannelChangeOverwrittenPermissionsEvent(
                            channel, ImplPermissions.EMPTY_PERMISSIONS, oldPermissions, role);
                });
            }
        });
    }

    /**
     * Handles a channel category update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleChannelCategory(JsonNode jsonChannel) {
        long channelCategoryId = jsonChannel.get("id").asLong();
        api.getChannelCategoryById(channelCategoryId).map(ImplChannelCategory.class::cast).ifPresent(channel -> {
            String oldName = channel.getName();
            String newName = jsonChannel.get("name").asText();
            if (!Objects.deepEquals(oldName, newName)) {
                channel.setName(newName);
                ServerChannelChangeNameEvent event =
                        new ServerChannelChangeNameEvent(channel, newName, oldName);

                List<ServerChannelChangeNameListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangeNameListeners());
                listeners.addAll(channel.getServer().getServerChannelChangeNameListeners());
                listeners.addAll(api.getServerChannelChangeNameListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangeName(event));
            }

            boolean oldNsfwFlaf = channel.isNsfw();
            boolean newNsfwFlag = jsonChannel.get("nsfw").asBoolean();
            if (oldNsfwFlaf != newNsfwFlag) {
                channel.setNsfwFlag(newNsfwFlag);
                ServerChannelChangeNsfwFlagEvent event =
                        new ServerChannelChangeNsfwFlagEvent(channel, newNsfwFlag, oldNsfwFlaf);

                List<ServerChannelChangeNsfwFlagListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangeNsfwFlagListeners());
                listeners.addAll(channel.getServer().getServerChannelChangeNsfwFlagListeners());
                listeners.addAll(api.getServerChannelChangeNsfwFlagListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangeNsfwFlag(event));
            }
        });
    }

    /**
     * Handles a server text channel update.
     *
     * @param jsonChannel The json channel data.
     */
    private void handleServerTextChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getTextChannelById(channelId).map(c -> ((ImplServerTextChannel) c)).ifPresent(channel -> {
            String oldTopic = channel.getTopic();
            String newTopic = jsonChannel.has("topic") && !jsonChannel.get("topic").isNull()
                    ? jsonChannel.get("topic").asText() : "";
            if (!oldTopic.equals(newTopic)) {
                channel.setTopic(newTopic);

                ServerTextChannelChangeTopicEvent event =
                        new ServerTextChannelChangeTopicEvent(channel, newTopic, oldTopic);

                List<ServerTextChannelChangeTopicListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerTextChannelChangeTopicListeners());
                listeners.addAll(channel.getServer().getServerTextChannelChangeTopicListeners());
                listeners.addAll(api.getServerTextChannelChangeTopicListeners());

                dispatchEvent(listeners, listener -> listener.onServerTextChannelChangeTopic(event));
            }

            boolean oldNsfwFlaf = channel.isNsfw();
            boolean newNsfwFlag = jsonChannel.get("nsfw").asBoolean();
            if (oldNsfwFlaf != newNsfwFlag) {
                channel.setNsfwFlag(newNsfwFlag);
                ServerChannelChangeNsfwFlagEvent event =
                        new ServerChannelChangeNsfwFlagEvent(channel, newNsfwFlag, oldNsfwFlaf);

                List<ServerChannelChangeNsfwFlagListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangeNsfwFlagListeners());
                listeners.addAll(channel.getServer().getServerChannelChangeNsfwFlagListeners());
                listeners.addAll(api.getServerChannelChangeNsfwFlagListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangeNsfwFlag(event));
            }

            ChannelCategory oldCategory = channel.getCategory().orElse(null);
            ChannelCategory newCategory = channel.getServer()
                    .getChannelCategoryById(jsonChannel.get("parent_id").asLong(-1)).orElse(null);
            if (!Objects.deepEquals(oldCategory, newCategory)) {
                channel.setParentId(newCategory == null ? -1 : newCategory.getId());
                ServerChannelChangeCategoryEvent event =
                        new ServerChannelChangeCategoryEvent(channel, newCategory, oldCategory);

                List<ServerChannelChangeCategoryListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangeCategoryListeners());
                listeners.addAll(channel.getServer().getServerChannelChangeCategoryListeners());
                listeners.addAll(api.getServerChannelChangeCategoryListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangeCategory(event));
            }
        });
    }

    /**
     * Handles a server voice channel update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleServerVoiceChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getServerVoiceChannelById(channelId).map(ImplServerVoiceChannel.class::cast).ifPresent(channel -> {
            int oldBitrate = channel.getBitrate();
            int newBitrate = jsonChannel.get("bitrate").asInt();
            if (oldBitrate != newBitrate) {
                channel.setBitrate(newBitrate);
                ServerVoiceChannelChangeBitrateEvent event =
                        new ServerVoiceChannelChangeBitrateEvent(channel, newBitrate, oldBitrate);

                List<ServerVoiceChannelChangeBitrateListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerVoiceChannelChangeBitrateListeners());
                listeners.addAll(channel.getServer().getServerVoiceChannelChangeBitrateListeners());
                listeners.addAll(api.getServerVoiceChannelChangeBitrateListeners());

                dispatchEvent(listeners, listener -> listener.onServerVoiceChannelChangeBitrate(event));
            }

            int oldUserLimit = channel.getUserLimit().orElse(0);
            int newUserLimit = jsonChannel.get("user_limit").asInt();
            if (oldUserLimit != newUserLimit) {
                channel.setUserLimit(newUserLimit);
                ServerVoiceChannelChangeUserLimitEvent event =
                        new ServerVoiceChannelChangeUserLimitEvent(channel, newUserLimit, oldUserLimit);

                List<ServerVoiceChannelChangeUserLimitListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerVoiceChannelChangeUserLimitListeners());
                listeners.addAll(channel.getServer().getServerVoiceChannelChangeUserLimitListeners());
                listeners.addAll(api.getServerVoiceChannelChangeUserLimitListeners());

                dispatchEvent(listeners, listener -> listener.onServerVoiceChannelChangeUserLimit(event));
            }

            ChannelCategory oldCategory = channel.getCategory().orElse(null);
            ChannelCategory newCategory = channel.getServer()
                    .getChannelCategoryById(jsonChannel.get("parent_id").asLong(-1)).orElse(null);
            if (!Objects.deepEquals(oldCategory, newCategory)) {
                channel.setParentId(newCategory == null ? -1 : newCategory.getId());
                ServerChannelChangeCategoryEvent event =
                        new ServerChannelChangeCategoryEvent(channel, newCategory, oldCategory);

                List<ServerChannelChangeCategoryListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getServerChannelChangeCategoryListeners());
                listeners.addAll(channel.getServer().getServerChannelChangeCategoryListeners());
                listeners.addAll(api.getServerChannelChangeCategoryListeners());

                dispatchEvent(listeners, listener -> listener.onServerChannelChangeCategory(event));
            }
        });
    }

    /**
     * Handles a private channel update.
     *
     * @param channel The channel data.
     */
    private void handlePrivateChannel(JsonNode channel) {
    }

    /**
     * Handles a group channel update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleGroupChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getGroupChannelById(channelId).map(ImplGroupChannel.class::cast).ifPresent(channel -> {
            String oldName = channel.getName().orElseThrow(AssertionError::new);
            String newName = jsonChannel.get("name").asText();
            if (!Objects.equals(oldName, newName)) {
                channel.setName(newName);

                GroupChannelChangeNameEvent event =
                        new GroupChannelChangeNameEvent(channel, newName, oldName);

                List<GroupChannelChangeNameListener> listeners = new ArrayList<>();
                listeners.addAll(channel.getGroupChannelChangeNameListeners());
                channel.getMembers().stream()
                        .map(User::getGroupChannelChangeNameListeners)
                        .forEach(listeners::addAll);
                listeners.addAll(api.getGroupChannelChangeNameListeners());

                dispatchEvent(listeners, listener -> listener.onGroupChannelChangeName(event));
            }
        });
    }

    /**
     * Dispatches a ServerChannelChangeOverwrittenPermissionsEvent.
     *
     * @param channel The channel of the event.
     * @param newPermissions The new overwritten permissions.
     * @param oldPermissions The old overwritten permissions.
     * @param entity The entity of the event.
     */
    private void dispatchServerChannelChangeOverwrittenPermissionsEvent(
            ServerChannel channel, Permissions newPermissions, Permissions oldPermissions, DiscordEntity entity) {
        if (newPermissions.equals(oldPermissions)) {
            // This can be caused by adding a user/role in a channels overwritten permissions without modifying
            // any of its values. We don't need to dispatch an event for this.
            return;
        }
        ServerChannelChangeOverwrittenPermissionsEvent event =
                new ServerChannelChangeOverwrittenPermissionsEvent(
                        channel, newPermissions, oldPermissions, entity);

        List<ServerChannelChangeOverwrittenPermissionsListener> listeners = new ArrayList<>();
        if (entity instanceof User) {
            listeners.addAll(((User) entity).getServerChannelChangeOverwrittenPermissionsListeners());
        }
        if (entity instanceof Role) {
            listeners.addAll(((Role) entity).getServerChannelChangeOverwrittenPermissionsListeners());
        }
        listeners.addAll(channel.getServerChannelChangeOverwrittenPermissionsListeners());
        listeners.addAll(channel.getServer().getServerChannelChangeOverwrittenPermissionsListeners());
        listeners.addAll(api.getServerChannelChangeOverwrittenPermissionsListeners());

        dispatchEvent(listeners, listener -> listener.onServerChannelChangeOverwrittenPermissions(event));
    }

}