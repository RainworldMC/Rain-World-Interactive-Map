package net.brickcraftdream.librainworldmc.extractor.storage;

import net.brickcraftdream.librainworldmc.extractor.model.mod.ModInfo;
import net.brickcraftdream.librainworldmc.extractor.model.room.*;
import net.brickcraftdream.librainworldmc.extractor.util.RegionFilter;
import net.brickcraftdream.librainworldmc.extractor.util.RegionMetadata;
import net.brickcraftdream.librainworldmc.extractor.config.ExtractorSettings;
import net.brickcraftdream.librainworldmc.extractor.config.RegionSchema;
import net.brickcraftdream.librainworldmc.extractor.model.region.RegionSource;
import net.brickcraftdream.librainworldmc.extractor.model.placed.PlacedObject;
import net.brickcraftdream.librainworldmc.extractor.model.placed.PlacedObjectData;
import net.brickcraftdream.librainworldmc.extractor.model.sound.AmbientSound;
import net.brickcraftdream.librainworldmc.extractor.model.sound.DirectionalSound;
import net.brickcraftdream.librainworldmc.extractor.model.sound.OmniDirectionalSound;
import net.brickcraftdream.librainworldmc.extractor.model.sound.SpotSound;
import net.brickcraftdream.librainworldmc.extractor.model.trigger.EventTrigger;
import net.brickcraftdream.librainworldmc.extractor.model.trigger.MusicEvent;
import net.brickcraftdream.librainworldmc.extractor.model.trigger.ShowProjectedImageEvent;
import net.brickcraftdream.librainworldmc.extractor.model.trigger.SpotTrigger;
import net.brickcraftdream.librainworldmc.extractor.model.trigger.StopMusicEvent;
import net.brickcraftdream.librainworldmc.extractor.model.trigger.TriggeredEvent;
import net.brickcraftdream.librainworldmc.extractor.util.SlugcatAvailability;
import net.brickcraftdream.librainworldmc.extractor.model.creature.StoredCreatureSpawn;
import net.brickcraftdream.librainworldmc.extractor.model.lineage.StoredLineage;
import net.brickcraftdream.librainworldmc.extractor.model.lineage.StoredLineageStage;
import net.brickcraftdream.librainworldmc.extractor.model.region.Region;
import net.brickcraftdream.librainworldmc.extractor.model.region.StoredRegion;
import net.brickcraftdream.librainworldmc.extractor.model.region.StoredRegionProperties;
import net.brickcraftdream.librainworldmc.extractor.model.creature.CreatureType;
import net.brickcraftdream.librainworldmc.extractor.model.creature.SpawnData;
import net.brickcraftdream.librainworldmc.extractor.model.timeline.Timeline;
import net.brickcraftdream.librainworldmc.extractor.model.region.WorldRegion;
import net.brickcraftdream.librainworldmc.extractor.format.RegionDataField;
import net.brickcraftdream.librainworldmc.extractor.format.RoomSettingsField;
import net.brickcraftdream.librainworldmc.extractor.format.TimelineMergeMode;
import net.brickcraftdream.librainworldmc.extractor.parsing.RegionPropertiesParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

public class RoomSettingsStore implements AutoCloseable, RoomSettingsSink {

    private static final String SETTINGS_COLUMNS = String.join(",\n            ",
            "file_path",
            "parent_template",
            "connections",
            "tags",
            "room_attrs",
            "danger_type",
            "rain_intensity",
            "rumble_intensity",
            "ceiling_drips",
            "wave_speed",
            "wave_amplitude",
            "wave_length",
            "second_wave_amplitude",
            "second_wave_length",
            "clouds",
            "grime",
            "bkg_drone_volume",
            "bkg_drone_no_threat_volume",
            "random_item_density",
            "random_item_spear_chance",
            "water_reflection_alpha",
            "palette",
            "effect_color_a",
            "effect_color_b",
            "terrain_light",
            "terrain_stain_amount",
            "terrain_stain_brightness",
            "terrain_stain_height",
            "terrain_waves",
            "terrain_edge_radius",
            "terrain_goo_height",
            "terrain_grain",
            "terrain_depth",
            "terrain_sky_fade",
            "terrain_palette",
            "room_specific_script",
            "wet_terrain",
            "fade_palette_index",
            "fade_palette_values",
            "terrain_fade_palette_name",
            "terrain_fade_palette_values");

    private static final String SETTINGS_PLACEHOLDERS = String.join(", ", java.util.Collections.nCopies(41, "?"));

    private static final String REGION_METADATA_COLUMNS = String.join(", ",
            "name", "timeline", "merged_timelines", "region_number", "room_count", "template_names", "map_name",
            "sources", "mod_ids", "available_timelines");

    private static final String REGION_SELECT_COLUMNS = REGION_METADATA_COLUMNS + ", " + RegionSchema.PROPERTY_COLUMNS;

    private final Connection connection;
    private ExtractorSettings settings = ExtractorSettings.defaults();

    private RoomSettingsStore(Connection connection) {
        this.connection = connection;
    }

    public ExtractorSettings getExtractorSettings() {
        return settings;
    }

    public void saveExtractorSettings(ExtractorSettings extractorSettings) throws SQLException {
        this.settings = extractorSettings;
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO extractor_metadata(key, value) VALUES (?, ?)
                """)) {
            statement.setString(1, "settings");
            statement.setString(2, extractorSettings.serialize());
            statement.executeUpdate();
        }
    }

    private void loadExtractorSettings() throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM extractor_metadata WHERE key = 'settings'")) {
            try(ResultSet resultSet = statement.executeQuery()) {
                if(resultSet.next()) {
                    settings = ExtractorSettings.deserialize(resultSet.getString("value"));
                }
            }
        }
        catch(SQLException ignored) {
            settings = ExtractorSettings.defaults();
        }
    }

    @Override
    public void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
    }

    @Override
    public void commitTransaction() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    @Override
    public void rollbackTransaction() {
        try {
            if(!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
        catch(SQLException ignored) {}
    }

    public static RoomSettingsStore open(Path databasePath) throws SQLException, IOException {
        if(databasePath.getParent() != null) {
            Files.createDirectories(databasePath.getParent());
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        RoomSettingsStore store = new RoomSettingsStore(connection);
        store.initializeSchema();
        store.loadExtractorSettings();
        return store;
    }

    private void initializeSchema() throws SQLException {
        try(Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL;");
            statement.execute("PRAGMA synchronous = NORMAL;");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS extractor_metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS regions (" +
                    "name TEXT NOT NULL, " +
                    "timeline TEXT NOT NULL DEFAULT '', " +
                    "merged_timelines TEXT NOT NULL DEFAULT '', " +
                    "region_number INTEGER NOT NULL, " +
                    "room_count INTEGER NOT NULL DEFAULT 0, " +
                    "template_names TEXT NOT NULL, " +
                    "map_name TEXT, " +
                    "sources TEXT NOT NULL DEFAULT '', " +
                    "mod_ids TEXT NOT NULL DEFAULT '', " +
                    "available_timelines TEXT NOT NULL DEFAULT '', " +
                    RegionSchema.PROPERTY_COLUMNS + ", " +
                    "PRIMARY KEY (name, timeline))");
            ensureColumn(statement, "regions", "timeline", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "regions", "merged_timelines", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "regions", "room_count", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "regions", "sources", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "regions", "mod_ids", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "regions", "available_timelines", "TEXT NOT NULL DEFAULT ''");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS room_settings_templates (" +
                    "region_name TEXT NOT NULL, template_name TEXT NOT NULL, " +
                    SETTINGS_COLUMNS + ", PRIMARY KEY (region_name, template_name))");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS room_settings (" +
                    "room_name TEXT NOT NULL, timeline TEXT NOT NULL, region_name TEXT NOT NULL, " +
                    "merged_timelines TEXT NOT NULL DEFAULT '', " +
                    SETTINGS_COLUMNS + ", PRIMARY KEY (room_name, timeline))");
            ensureColumn(statement, "room_settings", "merged_timelines", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "room_settings", "connections", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "room_settings", "tags", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "room_settings", "room_attrs", "TEXT");
            ensureColumn(statement, "room_settings_templates", "connections", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "room_settings_templates", "tags", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "room_settings_templates", "room_attrs", "TEXT");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_setting_effects (
                        owner_kind TEXT NOT NULL,
                        region_name TEXT NOT NULL,
                        room_name TEXT NOT NULL DEFAULT '',
                        template_name TEXT NOT NULL DEFAULT '',
                        timeline TEXT NOT NULL DEFAULT '',
                        effect_index INTEGER NOT NULL,
                        effect_type TEXT NOT NULL,
                        amount REAL NOT NULL,
                        extra_amounts TEXT,
                        panel_x REAL NOT NULL,
                        panel_y REAL NOT NULL,
                        inherited INTEGER NOT NULL,
                        over_write INTEGER NOT NULL,
                        unrecognized_attributes TEXT,
                        PRIMARY KEY (owner_kind, region_name, room_name, template_name, timeline, effect_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_ambient_sounds (
                        owner_kind TEXT NOT NULL,
                        region_name TEXT NOT NULL,
                        room_name TEXT NOT NULL DEFAULT '',
                        template_name TEXT NOT NULL DEFAULT '',
                        timeline TEXT NOT NULL DEFAULT '',
                        sound_index INTEGER NOT NULL,
                        sound_type TEXT NOT NULL,
                        sample TEXT NOT NULL,
                        volume REAL NOT NULL,
                        pitch REAL NOT NULL,
                        inherited INTEGER NOT NULL,
                        over_write INTEGER NOT NULL,
                        panel_x REAL NOT NULL,
                        panel_y REAL NOT NULL,
                        doppler_fac REAL,
                        direction_degrees REAL,
                        pos_x REAL,
                        pos_y REAL,
                        rad_handle_x REAL,
                        rad_handle_y REAL,
                        rad REAL,
                        taper REAL,
                        PRIMARY KEY (owner_kind, region_name, room_name, template_name, timeline, sound_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_triggers (
                        owner_kind TEXT NOT NULL,
                        region_name TEXT NOT NULL,
                        room_name TEXT NOT NULL DEFAULT '',
                        template_name TEXT NOT NULL DEFAULT '',
                        timeline TEXT NOT NULL DEFAULT '',
                        trigger_index INTEGER NOT NULL,
                        trigger_type TEXT NOT NULL,
                        active_to_cycle INTEGER NOT NULL,
                        active_from_cycle INTEGER NOT NULL,
                        delay INTEGER NOT NULL,
                        multi_use INTEGER NOT NULL,
                        fire_chance REAL NOT NULL,
                        entrance INTEGER NOT NULL,
                        karma INTEGER NOT NULL,
                        slugcats TEXT,
                        panel_x REAL NOT NULL,
                        panel_y REAL NOT NULL,
                        spot_pos_x REAL,
                        spot_pos_y REAL,
                        spot_rad_handle_x REAL,
                        spot_rad_handle_y REAL,
                        spot_rad REAL,
                        event_type TEXT,
                        ev_song_name TEXT,
                        ev_prio REAL,
                        ev_volume REAL,
                        ev_max_threat_level REAL,
                        ev_drone_tolerance REAL,
                        ev_fade_in_time REAL,
                        ev_loop INTEGER,
                        ev_one_song_per_cycle INTEGER,
                        ev_stop_at_death INTEGER,
                        ev_stop_at_gate INTEGER,
                        ev_rooms_range INTEGER,
                        ev_cycles_rest INTEGER,
                        ev_fade_out_time REAL,
                        ev_stop_type TEXT,
                        ev_after_encounter INTEGER,
                        ev_only_when_showing_direction INTEGER,
                        ev_from_cycle INTEGER,
                        PRIMARY KEY (owner_kind, region_name, room_name, template_name, timeline, trigger_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_placed_objects (
                        owner_kind TEXT NOT NULL,
                        region_name TEXT NOT NULL,
                        room_name TEXT NOT NULL DEFAULT '',
                        template_name TEXT NOT NULL DEFAULT '',
                        timeline TEXT NOT NULL DEFAULT '',
                        object_index INTEGER NOT NULL,
                        object_type TEXT NOT NULL,
                        pos_x REAL NOT NULL,
                        pos_y REAL NOT NULL,
                        active INTEGER NOT NULL,
                        deactivated_by_warp_filter INTEGER NOT NULL,
                        data_class TEXT,
                        data_raw TEXT,
                        data_handle_x REAL,
                        data_handle_y REAL,
                        data_panel_x REAL,
                        data_panel_y REAL,
                        data_strength REAL,
                        data_fade_with_sun INTEGER,
                        data_flat INTEGER,
                        data_color_type TEXT,
                        data_blink_type TEXT,
                        data_blink_rate REAL,
                        data_night_light INTEGER,
                        data_fixture_type TEXT,
                        data_random_seed INTEGER,
                        data_depth REAL,
                        data_rotation REAL,
                        data_length REAL,
                        data_brightness REAL,
                        data_direction REAL,
                        data_skull_seed INTEGER,
                        data_pearls_seed INTEGER,
                        data_insect_type TEXT,
                        data_density REAL,
                        data_item_type TEXT,
                        data_chance REAL,
                        data_min_regen INTEGER,
                        data_max_regen INTEGER,
                        data_pearl_type TEXT,
                        data_hidden INTEGER,
                        data_exit INTEGER,
                        data_handle2_x REAL,
                        data_handle2_y REAL,
                        data_handle3_x REAL,
                        data_handle3_y REAL,
                        data_from_depth REAL,
                        data_to_depth REAL,
                        data_intensity REAL,
                        data_noise REAL,
                        data_image_name TEXT,
                        data_vertices TEXT,
                        data_token_string TEXT,
                        data_is_blue INTEGER,
                        data_is_green INTEGER,
                        data_is_white INTEGER,
                        data_is_red INTEGER,
                        data_is_dev INTEGER,
                        data_available_to_players TEXT,
                        data_available_on_timelines TEXT,
                        data_min_ripple_level REAL,
                        data_max_ripple_level REAL,
                        data_stage INTEGER,
                        data_greater_or_equal INTEGER,
                        data_post_game INTEGER,
                        data_scale REAL,
                        data_chromatic_intensity REAL,
                        data_time_mult REAL,
                        data_l_intensity REAL,
                        data_shape TEXT,
                        data_noisiness REAL,
                        data_angle REAL,
                        data_pos_x REAL,
                        data_pos_y REAL,
                        data_start_x REAL,
                        data_start_y REAL,
                        data_end_x REAL,
                        data_end_y REAL,
                        data_permanent INTEGER,
                        data_radial INTEGER,
                        data_width REAL,
                        data_lifetime REAL,
                        data_lightning_param REAL,
                        data_lightning_type REAL,
                        data_impact INTEGER,
                        data_volume REAL,
                        data_sound_type INTEGER,
                        data_random INTEGER,
                        data_light INTEGER,
                        data_sprite_num INTEGER,
                        data_speed REAL,
                        data_duration REAL,
                        data_frequency REAL,
                        data_affect_group TEXT,
                        data_left_offset_x REAL,
                        data_left_offset_y REAL,
                        data_right_offset_x REAL,
                        data_right_offset_y REAL,
                        data_back_height REAL,
                        data_flow REAL,
                        data_dynamic INTEGER,
                        data_pre_delay REAL,
                        data_post_delay REAL,
                        data_index INTEGER,
                        data_dusk_palette INTEGER,
                        data_night_palette INTEGER,
                        data_terrain_palette TEXT,
                        data_abs_pulse INTEGER,
                        data_pulse_min REAL,
                        data_pulse_max REAL,
                        data_pulse_rate REAL,
                        data_scale_min REAL,
                        data_scale_max REAL,
                        data_interp_dur_min REAL,
                        data_interp_dur_max REAL,
                        data_interp_dist_min REAL,
                        data_interp_dist_max REAL,
                        data_dir_dev_min REAL,
                        data_dir_dev_max REAL,
                        data_dir_min REAL,
                        data_dir_max REAL,
                        data_color_h_min REAL,
                        data_color_h_max REAL,
                        data_color_s_min REAL,
                        data_color_s_max REAL,
                        data_color_l_min REAL,
                        data_color_l_max REAL,
                        data_interp_trans REAL,
                        data_alpha_trans REAL,
                        data_num_keyframes REAL,
                        data_sprite_type TEXT,
                        data_dir_lerp_type TEXT,
                        data_speed_lerp_type TEXT,
                        data_glow_rad REAL,
                        data_glow_strength REAL,
                        data_rotation_rate REAL,
                        PRIMARY KEY (owner_kind, region_name, room_name, template_name, timeline, object_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_creature_spawns (
                        region_name TEXT NOT NULL,
                        timeline TEXT NOT NULL DEFAULT '',
                        room_name TEXT NOT NULL DEFAULT '',
                        spawn_index INTEGER NOT NULL,
                        offscreen INTEGER NOT NULL DEFAULT 0,
                        den INTEGER NOT NULL,
                        creature_type TEXT NOT NULL,
                        amount INTEGER NOT NULL DEFAULT 1,
                        spawn_data TEXT,
                        PRIMARY KEY (region_name, timeline, room_name, spawn_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_lineages (
                        region_name TEXT NOT NULL,
                        timeline TEXT NOT NULL DEFAULT '',
                        room_name TEXT NOT NULL DEFAULT '',
                        lineage_index INTEGER NOT NULL,
                        offscreen INTEGER NOT NULL DEFAULT 0,
                        den INTEGER NOT NULL,
                        night_creature INTEGER NOT NULL DEFAULT 0,
                        conflict_index INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (region_name, timeline, room_name, lineage_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS room_lineage_stages (
                        region_name TEXT NOT NULL,
                        timeline TEXT NOT NULL DEFAULT '',
                        room_name TEXT NOT NULL DEFAULT '',
                        lineage_index INTEGER NOT NULL,
                        stage_index INTEGER NOT NULL,
                        creature_type TEXT NOT NULL,
                        progression_chance REAL NOT NULL DEFAULT 0,
                        spawn_data TEXT,
                        PRIMARY KEY (region_name, timeline, room_name, lineage_index, stage_index)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_room_settings_region ON room_settings(region_name)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS mods (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        version TEXT,
                        requirements TEXT,
                        workshop_id TEXT,
                        is_workshop_mod INTEGER NOT NULL DEFAULT 0,
                        modifies_regions INTEGER NOT NULL DEFAULT 0,
                        has_targeted_version_folder INTEGER NOT NULL DEFAULT 0,
                        has_newest_folder INTEGER NOT NULL DEFAULT 0,
                        priority INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS mod_regions (
                        mod_id TEXT NOT NULL,
                        region_name TEXT NOT NULL,
                        is_subregion INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (mod_id, region_name)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS mod_palettes (
                        mod_id TEXT NOT NULL,
                        palette_name TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        PRIMARY KEY (mod_id, palette_name)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_mod_regions_region ON mod_regions(region_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_mod_palettes_name ON mod_palettes(palette_name)");
        }
    }

    public void clearAll() throws SQLException {
        try(Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM room_lineage_stages");
            statement.execute("DELETE FROM room_lineages");
            statement.execute("DELETE FROM room_creature_spawns");
            statement.execute("DELETE FROM room_placed_objects");
            statement.execute("DELETE FROM room_triggers");
            statement.execute("DELETE FROM room_ambient_sounds");
            statement.execute("DELETE FROM room_setting_effects");
            statement.execute("DELETE FROM room_settings");
            statement.execute("DELETE FROM room_settings_templates");
            statement.execute("DELETE FROM regions");
            statement.execute("DELETE FROM mod_palettes");
            statement.execute("DELETE FROM mod_regions");
            statement.execute("DELETE FROM mods");
        }
    }

    public void saveRegion(WorldRegion region) throws SQLException {
        saveRegionTimeline(region.name, null, List.of(), region, new StoredRegionProperties(), null);
    }

    public void saveRegionTimelineEntry(ExtractedRoomDatabase.RegionTimelineEntry entry) throws SQLException {
        saveRegionTimeline(
                entry.regionName(),
                entry.timeline(),
                entry.mergedTimelines(),
                entry.regionNumber(),
                entry.roomCount(),
                entry.templateNames(),
                entry.mapName(),
                entry.properties(),
                entry.displayName());
    }

    void saveRegionTimeline(
            String regionName,
            Timeline timeline,
            List<Timeline> mergedTimelines,
            int regionNumber,
            int roomCount,
            List<String> templateNames,
            String mapName,
            StoredRegionProperties properties,
            String displayName) throws SQLException {
        RegionMetadata metadata = RegionMetadata.forRegion(regionName);
        String templateNamesJoined = String.join(",", templateNames);
        List<Timeline> mergedTimelineValues = mergedTimelines == null ? List.of() : mergedTimelines;
        String timelineKey = regionStorageTimelineKey(timeline, mergedTimelineValues);
        StoredRegionProperties propertiesToSave = settings.includes(RegionDataField.PROPERTIES)
                ? properties
                : new StoredRegionProperties();
        String displayNameToSave = settings.includes(RegionDataField.DISPLAY_NAME) ? displayName : null;

        try(PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO regions(" + REGION_METADATA_COLUMNS + ", " + RegionSchema.PROPERTY_COLUMNS +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + RegionSchema.PROPERTY_PLACEHOLDERS + ")")) {
            int index = 1;
            statement.setString(index++, regionName);
            statement.setString(index++, timelineKey);
            statement.setString(index++, encodeTimelines(mergedTimelineValues));
            statement.setInt(index++, regionNumber);
            statement.setInt(index++, roomCount);
            statement.setString(index++, templateNamesJoined);
            statement.setString(index++, mapName);
            statement.setString(index++, encodeEnumSet(metadata.sources()));
            statement.setString(index++, encodeStringList(metadata.modIds()));
            statement.setString(index++, encodeTimelines(metadata.timelines()));
            RegionPropertiesBinder.bind(statement, index, propertiesToSave, displayNameToSave);
            statement.executeUpdate();
        }
    }

    public void saveRegionTimeline(
            String regionName,
            Timeline timeline,
            List<Timeline> mergedTimelines,
            WorldRegion baseRegion,
            StoredRegionProperties properties,
            String displayName) throws SQLException {
        saveRegionTimeline(
                regionName,
                timeline,
                mergedTimelines,
                baseRegion.regionNumber,
                baseRegion.numberOfRooms,
                List.of(baseRegion.roomSettingTemplateNames),
                baseRegion.regionParams.mapName,
                properties,
                displayName);
    }

    public void saveRoomCreatureSpawns(
            String regionName,
            Timeline timeline,
            String roomName,
            boolean offscreen,
            List<StoredCreatureSpawn> spawns) throws SQLException {
        if(!settings.includes(RegionDataField.CREATURE_SPAWNERS) || spawns.isEmpty()) {
            return;
        }
        String timelineKey = timelineKey(timeline);
        String storageRoomName = offscreen ? "" : roomName;
        deleteRoomCreatureSpawns(regionName, timelineKey, storageRoomName, offscreen);

        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO room_creature_spawns(
                    region_name, timeline, room_name, spawn_index, offscreen, den, creature_type, amount, spawn_data
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for(int spawnIndex = 0; spawnIndex < spawns.size(); spawnIndex++) {
                StoredCreatureSpawn spawn = spawns.get(spawnIndex);
                statement.setString(1, regionName);
                statement.setString(2, timelineKey);
                statement.setString(3, storageRoomName);
                statement.setInt(4, spawnIndex);
                statement.setBoolean(5, offscreen);
                statement.setInt(6, spawn.den);
                statement.setString(7, encodeCreatureType(spawn.creatureType));
                statement.setInt(8, spawn.amount);
                statement.setString(9, encodeSpawnData(spawn.spawnData));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void saveRoomLineages(
            String regionName,
            Timeline timeline,
            String roomName,
            boolean offscreen,
            List<StoredLineage> lineages) throws SQLException {
        if(!settings.includes(RegionDataField.LINEAGES) || lineages.isEmpty()) {
            return;
        }
        String timelineKey = timelineKey(timeline);
        String storageRoomName = offscreen ? "" : roomName;
        deleteRoomLineages(regionName, timelineKey, storageRoomName, offscreen);

        try(PreparedStatement lineageStatement = connection.prepareStatement("""
                INSERT INTO room_lineages(
                    region_name, timeline, room_name, lineage_index, offscreen, den, night_creature, conflict_index
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """);
            PreparedStatement stageStatement = connection.prepareStatement("""
                INSERT INTO room_lineage_stages(
                    region_name, timeline, room_name, lineage_index, stage_index, creature_type, progression_chance, spawn_data
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for(int lineageIndex = 0; lineageIndex < lineages.size(); lineageIndex++) {
                StoredLineage lineage = lineages.get(lineageIndex);
                lineageStatement.setString(1, regionName);
                lineageStatement.setString(2, timelineKey);
                lineageStatement.setString(3, storageRoomName);
                lineageStatement.setInt(4, lineageIndex);
                lineageStatement.setBoolean(5, offscreen);
                lineageStatement.setInt(6, lineage.den);
                lineageStatement.setBoolean(7, lineage.nightCreature);
                lineageStatement.setInt(8, lineage.conflictIndex);
                lineageStatement.addBatch();

                for(StoredLineageStage stage : lineage.stages) {
                    stageStatement.setString(1, regionName);
                    stageStatement.setString(2, timelineKey);
                    stageStatement.setString(3, storageRoomName);
                    stageStatement.setInt(4, lineageIndex);
                    stageStatement.setInt(5, stage.stageIndex);
                    stageStatement.setString(6, encodeCreatureType(stage.creatureType));
                    stageStatement.setFloat(7, stage.progressionChance);
                    stageStatement.setString(8, encodeSpawnData(stage.spawnData));
                    stageStatement.addBatch();
                }
            }
            lineageStatement.executeBatch();
            stageStatement.executeBatch();
        }
    }

    public void saveTemplate(String regionName, String templateName, StoredRoomSettings settings) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO room_settings_templates(region_name, template_name, " +
                SETTINGS_COLUMNS + ") VALUES (?, ?, " + SETTINGS_PLACEHOLDERS + ")")) {
            int index = 1;
            statement.setString(index++, regionName);
            statement.setString(index++, templateName);
            bindSettings(statement, index, settings, true);
            statement.executeUpdate();
        }
        saveEffects(EffectOwner.template(regionName, templateName), settings, true);
        saveAmbientSounds(EffectOwner.template(regionName, templateName), settings);
        saveTriggers(EffectOwner.template(regionName, templateName), settings);
        savePlacedObjects(EffectOwner.template(regionName, templateName), settings);
    }

    public void saveRoomSettings(StoredRoomSettings settings) throws SQLException {
        String storageTimeline = storageTimelineKey(settings);
        String regionName = settings.regionName != null
                ? settings.regionName
                : settings.region != null ? settings.region.getRegionCode().toUpperCase(Locale.ROOT) : null;
        try(PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO room_settings(room_name, timeline, region_name, merged_timelines, " +
                SETTINGS_COLUMNS + ") VALUES (?, ?, ?, ?, " + SETTINGS_PLACEHOLDERS + ")")) {
            int index = 1;
            statement.setString(index++, settings.roomName);
            statement.setString(index++, storageTimeline);
            statement.setString(index++, regionName);
            statement.setString(index++, encodeTimelines(settings.mergedTimelines));
            bindSettings(statement, index, settings, false);
            statement.executeUpdate();
        }
        saveEffects(EffectOwner.room(regionName, settings.roomName, storageTimeline), settings, false);
        EffectOwner owner = EffectOwner.room(regionName, settings.roomName, storageTimeline);
        saveAmbientSounds(owner, settings);
        saveTriggers(owner, settings);
        savePlacedObjects(owner, settings);
    }

    @Override
    public void saveMod(ModInfo mod, int priority) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO mods(
                    id, name, version, requirements, workshop_id,
                    is_workshop_mod, modifies_regions, has_targeted_version_folder, has_newest_folder, priority
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, mod.id());
            statement.setString(2, mod.name());
            statement.setString(3, mod.version());
            statement.setString(4, encodeStringList(mod.requirements()));
            statement.setString(5, mod.workshopId());
            statement.setBoolean(6, mod.workshopMod());
            statement.setBoolean(7, mod.modifiesRegions());
            statement.setBoolean(8, mod.hasTargetedVersionFolder());
            statement.setBoolean(9, mod.hasNewestFolder());
            statement.setInt(10, priority);
            statement.executeUpdate();
        }
    }

    @Override
    public void saveModRegion(String modId, String regionName, boolean isSubregion) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO mod_regions(mod_id, region_name, is_subregion) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, modId);
            statement.setString(2, regionName);
            statement.setBoolean(3, isSubregion);
            statement.executeUpdate();
        }
    }

    @Override
    public void saveModPalette(String modId, String paletteName, String relativePath) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO mod_palettes(mod_id, palette_name, relative_path) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, modId);
            statement.setString(2, paletteName);
            statement.setString(3, relativePath);
            statement.executeUpdate();
        }
    }

    public List<ModInfo> findModsProvidingRegion(String regionName) throws SQLException {
        List<ModInfo> mods = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT m.* FROM mods m
                JOIN mod_regions r ON r.mod_id = m.id
                WHERE r.region_name = ?
                ORDER BY m.priority DESC
                """)) {
            statement.setString(1, regionName);
            try(ResultSet rs = statement.executeQuery()) {
                while(rs.next()) mods.add(readModInfo(rs));
            }
        }
        return mods;
    }

    public List<ModInfo> findModsProvidingPalette(String paletteName) throws SQLException {
        List<ModInfo> mods = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT m.* FROM mods m
                JOIN mod_palettes p ON p.mod_id = m.id
                WHERE p.palette_name = ?
                ORDER BY m.priority DESC
                """)) {
            statement.setString(1, paletteName);
            try(ResultSet rs = statement.executeQuery()) {
                while(rs.next()) mods.add(readModInfo(rs));
            }
        }
        return mods;
    }

    public boolean modProvidesPalette(String modId, String paletteName) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM mod_palettes WHERE mod_id = ? AND palette_name = ? LIMIT 1")) {
            statement.setString(1, modId);
            statement.setString(2, paletteName);
            try(ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int modPriority(String modId) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT priority FROM mods WHERE id = ?")) {
            statement.setString(1, modId);
            try(ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    public String findPaletteForRoom(String roomName) throws SQLException {
        StoredRoomSettings room = getRoomSettings(roomName);
        if(room != null && room.palette != null) {
            return "palette" + room.palette;
        }
        String regionName = room != null && room.regionName != null ? room.regionName : deriveRegionFromRoom(roomName);
        if(regionName == null) {
            return null;
        }
        StoredRegion region = getRegion(regionName);
        return region != null && region.palette != null ? "palette" + region.palette : null;
    }

    public String findFadePaletteForRoom(String roomName) throws SQLException {
        StoredRoomSettings room = getRoomSettings(roomName);
        if(room != null && room.fadePalette != null) {
            return "palette" + room.fadePalette.palette;
        }
        String regionName = room != null && room.regionName != null ? room.regionName : deriveRegionFromRoom(roomName);
        if(regionName == null) {
            return null;
        }
        StoredRegion region = getRegion(regionName);
        return region != null && region.fadePalette != null ? "palette" + region.fadePalette : null;
    }

    private static String deriveRegionFromRoom(String roomName) {
        if(roomName == null) return null;
        int idx = roomName.indexOf('_');
        return idx > 0 ? roomName.substring(0, idx) : roomName;
    }

    private static ModInfo readModInfo(ResultSet rs) throws SQLException {
        return new ModInfo(
                rs.getString("id"),
                Path.of(""),
                rs.getString("name"),
                rs.getString("version"),
                decodeStringList(rs.getString("requirements")),
                rs.getBoolean("has_targeted_version_folder"),
                rs.getBoolean("has_newest_folder"),
                rs.getBoolean("modifies_regions"),
                rs.getString("workshop_id"),
                rs.getBoolean("is_workshop_mod"));
    }

    public List<ExtractedRoomDatabase.ModEntry> listAllMods() throws SQLException {
        List<ExtractedRoomDatabase.ModEntry> entries = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(
                    "SELECT * FROM mods ORDER BY priority")) {
            while(rs.next()) {
                entries.add(new ExtractedRoomDatabase.ModEntry(readModInfo(rs), rs.getInt("priority")));
            }
        }
        return entries;
    }

    public List<ExtractedRoomDatabase.ModRegionEntry> listAllModRegions() throws SQLException {
        List<ExtractedRoomDatabase.ModRegionEntry> entries = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(
                    "SELECT mod_id, region_name, is_subregion FROM mod_regions ORDER BY mod_id, region_name")) {
            while(rs.next()) {
                entries.add(new ExtractedRoomDatabase.ModRegionEntry(
                        rs.getString("mod_id"),
                        rs.getString("region_name"),
                        rs.getBoolean("is_subregion")));
            }
        }
        return entries;
    }

    public List<ExtractedRoomDatabase.ModPaletteEntry> listAllModPalettes() throws SQLException {
        List<ExtractedRoomDatabase.ModPaletteEntry> entries = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(
                    "SELECT mod_id, palette_name, relative_path FROM mod_palettes ORDER BY mod_id, palette_name")) {
            while(rs.next()) {
                entries.add(new ExtractedRoomDatabase.ModPaletteEntry(
                        rs.getString("mod_id"),
                        rs.getString("palette_name"),
                        rs.getString("relative_path")));
            }
        }
        return entries;
    }

    public List<StoredRegion> listAllRegions() throws SQLException {
        return listRegions(RegionFilter.all());
    }

    public List<StoredRegion> listRegions(RegionFilter filter) throws SQLException {
        List<StoredRegion> regions = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                    "SELECT " + REGION_SELECT_COLUMNS + " FROM regions WHERE timeline = '' ORDER BY region_number")) {
            while(resultSet.next()) {
                StoredRegion region = readRegion(resultSet);
                if(filter == null || filter.matches(region)) {
                    regions.add(region);
                }
            }
        }
        return regions;
    }

    public List<StoredRegion> listRegionsForTimeline(Timeline timeline) throws SQLException {
        return listRegions(RegionFilter.forTimeline(timeline));
    }

    public List<StoredRegion> listRegionsBySource(RegionSource source) throws SQLException {
        return listRegions(RegionFilter.forSource(source));
    }

    public StoredRegion getRegion(String regionName) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT " + REGION_SELECT_COLUMNS + " FROM regions WHERE name = ? AND timeline = ''")) {
            statement.setString(1, regionName);
            try(ResultSet resultSet = statement.executeQuery()) {
                if(resultSet.next()) {
                    return readRegion(resultSet);
                }
            }
        }
        return null;
    }

    public StoredRegion getRegion(String regionName, Timeline timeline) throws SQLException {
        if(settings.timelineMergeMode() == TimelineMergeMode.MERGE_IDENTICAL) {
            for(StoredRegionTimelineVariant variant : listRegionTimelineVariants(regionName)) {
                if(variant.mergedTimelines.isEmpty()) {
                    if(timelineKey(variant.timeline).equals(timelineKey(timeline))) {
                        return variant.region;
                    }
                }
                else if(variant.mergedTimelines.contains(timeline)) {
                    return variant.region;
                }
            }
            return getRegion(regionName);
        }
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT " + REGION_SELECT_COLUMNS + " FROM regions WHERE name = ? AND timeline = ?")) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey(timeline));
            try(ResultSet resultSet = statement.executeQuery()) {
                if(resultSet.next()) {
                    return readRegion(resultSet);
                }
            }
        }
        return getRegion(regionName);
    }

    private List<StoredRegionTimelineVariant> listRegionTimelineVariants(String regionName) throws SQLException {
        List<StoredRegionTimelineVariant> variants = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT " + REGION_SELECT_COLUMNS + " FROM regions WHERE name = ? ORDER BY timeline")) {
            statement.setString(1, regionName);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    StoredRegion region = readRegion(resultSet);
                    Timeline timeline = timelineFromKey(resultSet.getString("timeline"));
                    List<Timeline> mergedTimelines = decodeTimelines(resultSet.getString("merged_timelines"));
                    variants.add(new StoredRegionTimelineVariant(region, timeline, mergedTimelines));
                }
            }
        }
        return variants;
    }

    public List<StoredRoomSettings> listRoomsInRegion(String regionName) throws SQLException {
        List<StoredRoomSettings> rooms = new ArrayList<>();
        String storageTimeline = primaryStorageTimeline();
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT room_name FROM room_settings WHERE region_name = ? AND timeline = ? ORDER BY room_name")) {
            statement.setString(1, regionName);
            statement.setString(2, storageTimeline);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    String roomName = resultSet.getString("room_name");
                    StoredRoomSettings settings = getRoomSettings(roomName);
                    if(settings == null) {
                        settings = new StoredRoomSettings();
                        settings.roomName = roomName;
                        settings.regionName = regionName;
                        settings.region = Region.fromStoredName(regionName);
                    }
                    populateRoomSettings(settings, null);
                    rooms.add(settings);
                }
            }
        }
        return rooms;
    }

    private String primaryStorageTimeline() {
        return switch(settings.timelineMergeMode()) {
            case DEFAULT_ONLY, PRIMARY_WHITE, MERGE_IDENTICAL, SEPARATE_ENTRIES -> "";
        };
    }

    public List<StoredRoomSettings> listRoomsInRegion(StoredRegion region) throws SQLException {
        return listRoomsInRegion(region.name);
    }

    public List<String> listRoomNames(String regionName) throws SQLException {
        List<String> names = new ArrayList<>();
        String storageTimeline = primaryStorageTimeline();
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT room_name FROM room_settings WHERE region_name = ? AND timeline = ? ORDER BY room_name")) {
            statement.setString(1, regionName);
            statement.setString(2, storageTimeline);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    names.add(resultSet.getString("room_name"));
                }
            }
        }
        return names;
    }

    public List<StoredCreatureSpawn> listRoomCreatureSpawns(String regionName, String roomName, Timeline timeline) throws SQLException {
        List<StoredCreatureSpawn> spawns = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT den, creature_type, amount, spawn_data
                FROM room_creature_spawns
                WHERE region_name = ? AND timeline = ? AND room_name = ? AND offscreen = 0
                ORDER BY spawn_index
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey(timeline));
            statement.setString(3, roomName);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    spawns.add(readCreatureSpawn(resultSet, false));
                }
            }
        }
        return spawns;
    }

    public List<StoredLineage> listRoomLineages(String regionName, String roomName, Timeline timeline) throws SQLException {
        String timelineKey = timelineKey(timeline);
        List<StoredLineage> lineages = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT lineage_index, den, night_creature, conflict_index
                FROM room_lineages
                WHERE region_name = ? AND timeline = ? AND room_name = ? AND offscreen = 0
                ORDER BY lineage_index
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            statement.setString(3, roomName);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    StoredLineage lineage = new StoredLineage();
                    lineage.timeline = timeline;
                    lineage.roomName = roomName;
                    lineage.den = resultSet.getInt("den");
                    lineage.nightCreature = readBoolean(resultSet, "night_creature");
                    lineage.conflictIndex = resultSet.getInt("conflict_index");
                    lineages.add(lineage);
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT lineage_index, stage_index, creature_type, progression_chance, spawn_data
                FROM room_lineage_stages
                WHERE region_name = ? AND timeline = ? AND room_name = ?
                ORDER BY lineage_index, stage_index
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            statement.setString(3, roomName);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    int lineageIndex = resultSet.getInt("lineage_index");
                    if(lineageIndex >= lineages.size()) continue;
                    lineages.get(lineageIndex).stages.add(readLineageStage(resultSet));
                }
            }
        }
        return lineages;
    }

    public List<StoredCreatureSpawn> listOffscreenCreatureSpawns(String regionName, Timeline timeline) throws SQLException {
        List<StoredCreatureSpawn> spawns = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT den, creature_type, amount, spawn_data
                FROM room_creature_spawns
                WHERE region_name = ? AND timeline = ? AND room_name = '' AND offscreen = 1
                ORDER BY spawn_index
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey(timeline));
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    spawns.add(readCreatureSpawn(resultSet, true));
                }
            }
        }
        return spawns;
    }

    public List<StoredLineage> listOffscreenLineages(String regionName, Timeline timeline) throws SQLException {
        String timelineKey = timelineKey(timeline);
        List<StoredLineage> lineages = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT lineage_index, den, night_creature, conflict_index
                FROM room_lineages
                WHERE region_name = ? AND timeline = ? AND room_name = '' AND offscreen = 1
                ORDER BY lineage_index
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    StoredLineage lineage = new StoredLineage();
                    lineage.timeline = timeline;
                    lineage.offscreen = true;
                    lineage.den = resultSet.getInt("den");
                    lineage.nightCreature = readBoolean(resultSet, "night_creature");
                    lineage.conflictIndex = resultSet.getInt("conflict_index");
                    lineages.add(lineage);
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT lineage_index, stage_index, creature_type, progression_chance, spawn_data
                FROM room_lineage_stages
                WHERE region_name = ? AND timeline = ? AND room_name = ''
                ORDER BY lineage_index, stage_index
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    int lineageIndex = resultSet.getInt("lineage_index");
                    if(lineageIndex >= lineages.size()) continue;
                    lineages.get(lineageIndex).stages.add(readLineageStage(resultSet));
                }
            }
        }
        return lineages;
    }

    public List<Timeline> listTimelinesForRoom(String roomName) throws SQLException {
        List<Timeline> timelines = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT timeline, merged_timelines FROM room_settings WHERE room_name = ? ORDER BY timeline")) {
            statement.setString(1, roomName);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    String merged = resultSet.getString("merged_timelines");
                    if(merged != null && !merged.isEmpty()) {
                        timelines.addAll(decodeTimelines(merged));
                    }
                    else {
                        timelines.add(timelineFromKey(resultSet.getString("timeline")));
                    }
                }
            }
        }
        return timelines;
    }

    public StoredRoomSettings getRoomSettings(String roomName) throws SQLException {
        return switch(settings.timelineMergeMode()) {
            case DEFAULT_ONLY, PRIMARY_WHITE -> getRoomSettingsByStorageTimeline(roomName, "");
            case MERGE_IDENTICAL -> getMergedRoomSettings(roomName);
            case SEPARATE_ENTRIES -> getRoomSettingsByStorageTimeline(roomName, "");
        };
    }

    public StoredRoomSettings getRoom(String roomName) throws SQLException {
        StoredRoomSettings settings = getRoomSettings(roomName);
        if(settings == null) {
            return null;
        }
        populateRoomSettings(settings, null);
        return settings;
    }

    public StoredRoomSettings getRoom(String roomName, Timeline timeline) throws SQLException {
        StoredRoomSettings settings = getRoomSettings(roomName, timeline);
        if(settings == null) {
            return null;
        }
        populateRoomSettings(settings, timeline);
        return settings;
    }

    public StoredRoomSettings getRoomSettings(String roomName, Timeline timeline) throws SQLException {
        if(settings.timelineMergeMode() == TimelineMergeMode.MERGE_IDENTICAL) {
            for(StoredRoomSettings variant : listRoomSettingsVariants(roomName)) {
                if(variant.mergedTimelines.isEmpty()) {
                    if(timelineKey(variant.timeline).equals(timelineKey(timeline))) {
                        return variant;
                    }
                }
                else if(variant.mergedTimelines.contains(timeline)) {
                    return variant;
                }
            }
            return null;
        }
        return getRoomSettingsByStorageTimeline(roomName, timelineKey(timeline));
    }

    private StoredRoomSettings getMergedRoomSettings(String roomName) throws SQLException {
        List<StoredRoomSettings> variants = listRoomSettingsVariants(roomName);
        if(variants.isEmpty()) {
            return null;
        }
        if(variants.size() == 1) {
            return variants.get(0);
        }
        for(StoredRoomSettings variant : variants) {
            if(variant.mergedTimelines.contains(null)) {
                return variant;
            }
        }
        return variants.get(0);
    }

    public List<StoredRoomSettings> listRoomSettingsVariants(String roomName) throws SQLException {
        List<StoredRoomSettings> variants = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT room_name, timeline, region_name, merged_timelines, " + SETTINGS_COLUMNS +
                " FROM room_settings WHERE room_name = ? ORDER BY timeline")) {
            statement.setString(1, roomName);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    String storageTimeline = resultSet.getString("timeline");
                    StoredRoomSettings roomSettings = readSettings(resultSet, "room_name", "region_name", "timeline");
                    String merged = resultSet.getString("merged_timelines");
                    if(merged != null && !merged.isEmpty()) {
                        roomSettings.mergedTimelines.addAll(decodeTimelines(merged));
                    }
                    loadEffects(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    loadAmbientSounds(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    loadTriggers(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    loadPlacedObjects(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    variants.add(roomSettings);
                }
            }
        }
        return variants;
    }

    private StoredRoomSettings getRoomSettingsByStorageTimeline(String roomName, String storageTimeline) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT room_name, timeline, region_name, merged_timelines, " + SETTINGS_COLUMNS +
                " FROM room_settings WHERE room_name = ? AND timeline = ?")) {
            statement.setString(1, roomName);
            statement.setString(2, storageTimeline);
            try(ResultSet resultSet = statement.executeQuery()) {
                if(resultSet.next()) {
                    StoredRoomSettings roomSettings = readSettings(resultSet, "room_name", "region_name", "timeline");
                    String merged = resultSet.getString("merged_timelines");
                    if(merged != null && !merged.isEmpty()) {
                        roomSettings.mergedTimelines.addAll(decodeTimelines(merged));
                    }
                    loadEffects(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    loadAmbientSounds(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    loadTriggers(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    loadPlacedObjects(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                    return roomSettings;
                }
            }
        }
        return null;
    }

    public StoredRoomSettings getTemplate(String regionName, String templateName) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT region_name, template_name, " + SETTINGS_COLUMNS + " FROM room_settings_templates WHERE region_name = ? AND template_name = ?")) {
            statement.setString(1, regionName);
            statement.setString(2, templateName);
            try(ResultSet resultSet = statement.executeQuery()) {
                if(resultSet.next()) {
                    StoredRoomSettings settings = readTemplateSettings(resultSet);
                    loadEffects(EffectOwner.template(regionName, templateName), settings);
                    loadAmbientSounds(EffectOwner.template(regionName, templateName), settings);
                    loadTriggers(EffectOwner.template(regionName, templateName), settings);
                    loadPlacedObjects(EffectOwner.template(regionName, templateName), settings);
                    return settings;
                }
            }
        }
        return null;
    }

    public int countRoomSettings() throws SQLException {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM room_settings")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    public List<String> listAllRegionNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                    "SELECT DISTINCT name FROM regions WHERE timeline = '' ORDER BY region_number")) {
            while(resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        }
        return names;
    }

    public void deleteRegion(String regionName) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM regions WHERE name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM room_settings_templates WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM room_settings WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM room_setting_effects WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM room_ambient_sounds WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM room_triggers WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM room_placed_objects WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM room_creature_spawns WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM room_lineages WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM room_lineage_stages WHERE region_name = ?")) {
            statement.setString(1, regionName);
            statement.executeUpdate();
        }
    }

    public List<StoredRoomSettings> listAllRoomSettingsRows() throws SQLException {
        List<StoredRoomSettings> rows = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                    "SELECT room_name, timeline, region_name, merged_timelines, " + SETTINGS_COLUMNS +
                    " FROM room_settings ORDER BY region_name, room_name, timeline")) {
            while(resultSet.next()) {
                String storageTimeline = resultSet.getString("timeline");
                StoredRoomSettings roomSettings = readSettings(resultSet, "room_name", "region_name", "timeline");
                String merged = resultSet.getString("merged_timelines");
                if(merged != null && !merged.isEmpty()) {
                    roomSettings.mergedTimelines.addAll(decodeTimelines(merged));
                }
                loadEffects(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                loadAmbientSounds(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                loadTriggers(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                loadPlacedObjects(EffectOwner.room(roomSettings.regionName, roomSettings.roomName, storageTimeline), roomSettings);
                rows.add(roomSettings);
            }
        }
        return rows;
    }

    public List<ExtractedRoomDatabase.TemplateEntry> listAllTemplates() throws SQLException {
        List<ExtractedRoomDatabase.TemplateEntry> templates = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                    "SELECT region_name, template_name, " + SETTINGS_COLUMNS +
                    " FROM room_settings_templates ORDER BY region_name, template_name")) {
            while(resultSet.next()) {
                String regionName = resultSet.getString("region_name");
                String templateName = resultSet.getString("template_name");
                StoredRoomSettings settings = readTemplateSettings(resultSet);
                loadEffects(EffectOwner.template(regionName, templateName), settings);
                loadAmbientSounds(EffectOwner.template(regionName, templateName), settings);
                loadTriggers(EffectOwner.template(regionName, templateName), settings);
                loadPlacedObjects(EffectOwner.template(regionName, templateName), settings);
                templates.add(new ExtractedRoomDatabase.TemplateEntry(regionName, templateName, settings));
            }
        }
        return templates;
    }

    public List<ExtractedRoomDatabase.RegionTimelineEntry> listAllRegionTimelines() throws SQLException {
        List<ExtractedRoomDatabase.RegionTimelineEntry> entries = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                    "SELECT " + REGION_SELECT_COLUMNS + " FROM regions ORDER BY region_number, timeline")) {
            while(resultSet.next()) {
                StoredRegion region = readRegion(resultSet);
                Timeline timeline = timelineFromKey(resultSet.getString("timeline"));
                List<Timeline> mergedTimelines = decodeTimelines(resultSet.getString("merged_timelines"));
                StoredRegionProperties properties = readRegionProperties(region);
                entries.add(new ExtractedRoomDatabase.RegionTimelineEntry(
                        region.name,
                        timeline,
                        mergedTimelines,
                        region.regionNumber,
                        region.roomCount,
                        new ArrayList<>(region.templateNames),
                        region.mapName,
                        properties,
                        region.displayName));
            }
        }
        return entries;
    }

    public List<ExtractedRoomDatabase.CreatureSpawnGroup> listAllCreatureSpawnGroups() throws SQLException {
        List<ExtractedRoomDatabase.CreatureSpawnGroup> groups = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT region_name, timeline, room_name, offscreen
                FROM room_creature_spawns
                GROUP BY region_name, timeline, room_name, offscreen
                ORDER BY region_name, timeline, room_name, offscreen
                """)) {
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    String regionName = resultSet.getString("region_name");
                    Timeline timeline = timelineFromKey(resultSet.getString("timeline"));
                    String roomName = resultSet.getString("room_name");
                    boolean offscreen = readBoolean(resultSet, "offscreen");
                    List<StoredCreatureSpawn> spawns = offscreen
                            ? listOffscreenCreatureSpawns(regionName, timeline)
                            : listRoomCreatureSpawns(regionName, roomName, timeline);
                    groups.add(new ExtractedRoomDatabase.CreatureSpawnGroup(
                            regionName,
                            timeline,
                            roomName,
                            offscreen,
                            spawns));
                }
            }
        }
        return groups;
    }

    public List<ExtractedRoomDatabase.LineageGroup> listAllLineageGroups() throws SQLException {
        List<ExtractedRoomDatabase.LineageGroup> groups = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT region_name, timeline, room_name, offscreen
                FROM room_lineages
                GROUP BY region_name, timeline, room_name, offscreen
                ORDER BY region_name, timeline, room_name, offscreen
                """)) {
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    String regionName = resultSet.getString("region_name");
                    Timeline timeline = timelineFromKey(resultSet.getString("timeline"));
                    String roomName = resultSet.getString("room_name");
                    boolean offscreen = readBoolean(resultSet, "offscreen");
                    List<StoredLineage> lineages = offscreen
                            ? listOffscreenLineages(regionName, timeline)
                            : listRoomLineages(regionName, roomName, timeline);
                    groups.add(new ExtractedRoomDatabase.LineageGroup(
                            regionName, timeline, roomName, offscreen, lineages));
                }
            }
        }
        return groups;
    }

    private StoredRegionProperties readRegionProperties(StoredRegion region) {
        StoredRegionProperties properties = new StoredRegionProperties();
        properties.batDepleteCyclesMin = region.batDepleteCyclesMin;
        properties.batDepleteCyclesMax = region.batDepleteCyclesMax;
        properties.batDepleteCyclesMaxIfLessThanTwoLeft = region.batDepleteCyclesMaxIfLessThanTwoLeft;
        properties.batDepleteCyclesMaxIfLessThanFiveLeft = region.batDepleteCyclesMaxIfLessThanFiveLeft;
        properties.overseersSpawnChance = region.overseersSpawnChance;
        properties.playerGuideOverseerSpawnChance = region.playerGuideOverseerSpawnChance;
        properties.overseersMin = region.overseersMin;
        properties.overseersMax = region.overseersMax;
        properties.batsPerActiveSwarmRoom = region.batsPerActiveSwarmRoom;
        properties.batsPerInactiveSwarmRoom = region.batsPerInactiveSwarmRoom;
        properties.scavsMin = region.scavsMin;
        properties.scavsMax = region.scavsMax;
        properties.scavsSpawnChance = region.scavsSpawnChance;
        properties.cloudsStart = region.cloudsStart;
        properties.cloudsEnd = region.cloudsEnd;
        properties.corruptionEffectColor = region.corruptionEffectColor;
        properties.corruptionEyeColor = region.corruptionEyeColor;
        properties.kelpColor = region.kelpColor;
        properties.albinos = region.albinos;
        properties.blackSalamanderChance = region.blackSalamanderChance;
        properties.proceduralMusicBank = region.proceduralMusicBank;
        properties.scavengerDelayInitialMin = region.scavengerDelayInitialMin;
        properties.scavengerDelayInitialMax = region.scavengerDelayInitialMax;
        properties.scavengerDelayRepeatMin = region.scavengerDelayRepeatMin;
        properties.scavengerDelayRepeatMax = region.scavengerDelayRepeatMax;
        properties.slugPupSpawnChance = region.slugPupSpawnChance;
        properties.glacialWasteland = region.glacialWasteland;
        properties.earlyCycleChance = region.earlyCycleChance;
        properties.earlyCycleFloodChance = region.earlyCycleFloodChance;
        properties.rotPalette = region.rotPalette;
        properties.mudColor = region.mudColor;
        properties.fuseSize = region.fuseSize;
        properties.mapSkyLayers = region.mapSkyLayers;
        properties.hideTimer = region.hideTimer;
        properties.waterColorOverride = region.waterColorOverride;
        properties.globalCreatureFlags = region.globalCreatureFlags;
        properties.subregions.addAll(region.subregions);
        properties.boxWormColor = region.boxWormColor;
        properties.rippleSpiderThreshold = region.rippleSpiderThreshold;
        properties.unrecognized.putAll(region.unrecognizedProperties);
        properties.palette = region.palette;
        properties.fadePalette = region.fadePalette;
        return properties;
    }

    private void populateRoomSettings(StoredRoomSettings settings, Timeline timeline) throws SQLException {
        String storageTimeline = timeline != null ? timelineKey(timeline) : primaryStorageTimeline();
        settings.connections.clear();
        settings.tags.clear();
        settings.roomAttrs.clear();
        settings.creatureSpawns.clear();
        settings.lineages.clear();
        try(PreparedStatement statement = connection.prepareStatement(
                "SELECT connections, tags, room_attrs FROM room_settings WHERE region_name = ? AND timeline = ? AND room_name = ?")) {
            statement.setString(1, settings.regionName);
            statement.setString(2, storageTimeline);
            statement.setString(3, settings.roomName);
            try(ResultSet resultSet = statement.executeQuery()) {
                if(resultSet.next()) {
                    settings.connections.addAll(decodeStringList(resultSet.getString("connections")));
                    settings.tags.addAll(decodeStringList(resultSet.getString("tags")).stream().map(RoomTag::fromString).filter(t -> t != RoomTag.UNKNOWN).toList());
                    settings.roomAttrs.addAll(RegionPropertiesParser.decodeRoomAttrs(resultSet.getString("room_attrs")));
                }
            }
        }
        settings.creatureSpawns.addAll(listRoomCreatureSpawns(settings.regionName, settings.roomName, timeline));
        settings.lineages.addAll(listRoomLineages(settings.regionName, settings.roomName, timeline));
    }

    private StoredCreatureSpawn readCreatureSpawn(ResultSet resultSet, boolean offscreen) throws SQLException {
        StoredCreatureSpawn spawn = new StoredCreatureSpawn();
        spawn.offscreen = offscreen;
        spawn.den = resultSet.getInt("den");
        spawn.creatureType = decodeCreatureType(resultSet.getString("creature_type"));
        spawn.amount = resultSet.getInt("amount");
        spawn.spawnData = decodeSpawnData(resultSet.getString("spawn_data"));
        return spawn;
    }

    private StoredLineageStage readLineageStage(ResultSet resultSet) throws SQLException {
        StoredLineageStage stage = new StoredLineageStage();
        stage.stageIndex = resultSet.getInt("stage_index");
        stage.creatureType = decodeCreatureType(resultSet.getString("creature_type"));
        stage.progressionChance = resultSet.getFloat("progression_chance");
        stage.spawnData = decodeSpawnData(resultSet.getString("spawn_data"));
        return stage;
    }

    private static String encodeCreatureType(CreatureType creatureType) {
        return creatureType == null ? CreatureType.UNKNOWN.name() : creatureType.name();
    }

    private static CreatureType decodeCreatureType(String value) {
        if(value == null || value.isBlank()) {
            return CreatureType.UNKNOWN;
        }
        try {
            return CreatureType.valueOf(value);
        }
        catch(IllegalArgumentException ignored) {
            return CreatureType.fromString(value);
        }
    }

    private static String encodeSpawnData(SpawnData spawnData) {
        return SpawnData.format(spawnData);
    }

    private static SpawnData decodeSpawnData(String value) {
        return SpawnData.parse(value);
    }

    private void deleteRoomCreatureSpawns(String regionName, String timelineKey, String roomName, boolean offscreen) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM room_creature_spawns
                WHERE region_name = ? AND timeline = ? AND room_name = ? AND offscreen = ?
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            statement.setString(3, roomName);
            statement.setBoolean(4, offscreen);
            statement.executeUpdate();
        }
    }

    private void deleteRoomLineages(String regionName, String timelineKey, String roomName, boolean offscreen) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM room_lineage_stages
                WHERE region_name = ? AND timeline = ? AND room_name = ?
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            statement.setString(3, roomName);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM room_lineages
                WHERE region_name = ? AND timeline = ? AND room_name = ? AND offscreen = ?
                """)) {
            statement.setString(1, regionName);
            statement.setString(2, timelineKey);
            statement.setString(3, roomName);
            statement.setBoolean(4, offscreen);
            statement.executeUpdate();
        }
    }

    private static StoredRegion readRegion(ResultSet resultSet) throws SQLException {
        StoredRegion region = new StoredRegion();
        region.name = resultSet.getString("name");
        region.region = Region.fromStoredName(region.name);
        region.regionNumber = resultSet.getInt("region_number");
        region.roomCount = resultSet.getInt("room_count");
        region.mapName = resultSet.getString("map_name");
        String templateNames = resultSet.getString("template_names");
        if(templateNames != null && !templateNames.isEmpty()) {
            region.templateNames.addAll(Arrays.asList(templateNames.split(",")));
        }
        region.sources.addAll(decodeRegionSources(resultSet.getString("sources")));
        region.modIds.addAll(decodeStringList(resultSet.getString("mod_ids")));
        region.timelines.addAll(decodeTimelines(resultSet.getString("available_timelines")));
        RegionPropertiesBinder.read(resultSet, region);
        return region;
    }

    private static void ensureColumn(Statement statement, String table, String column, String definition) throws SQLException {
        try {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
        catch(SQLException ignored) {
            // column already exists
        }
    }

    private static String encodeEnumSet(java.util.EnumSet<RegionSource> values) {
        return values.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static java.util.EnumSet<RegionSource> decodeRegionSources(String encoded) {
        java.util.EnumSet<RegionSource> sources = java.util.EnumSet.noneOf(RegionSource.class);
        if(encoded == null || encoded.isEmpty()) {
            return sources;
        }
        for(String part : encoded.split(",")) {
            if(!part.isEmpty()) {
                sources.add(RegionSource.valueOf(part));
            }
        }
        return sources;
    }

    static String encodeStringList(List<String> values) {
        if(values.isEmpty()) {
            return "";
        }
        return String.join(",", values);
    }

    private static List<String> decodeStringList(String encoded) {
        if(encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(encoded.split(","));
    }

    private static String encodeTimelines(List<Timeline> timelines) {
        return timelines.stream()
                .map(timeline -> timeline != null ? timeline.getValue() : "")
                .collect(Collectors.joining(","));
    }

    private static List<Timeline> decodeTimelines(String encoded) {
        List<Timeline> timelines = new ArrayList<>();
        if(encoded == null || encoded.isEmpty()) {
            return timelines;
        }
        for(String part : encoded.split(",")) {
            if(part.isEmpty()) {
                timelines.add(null);
            }
            else {
                timelines.add(Timeline.fromValue(part));
            }
        }
        return timelines;
    }

    private static StoredRoomSettings readTemplateSettings(ResultSet resultSet) throws SQLException {
        StoredRoomSettings settings = readSettings(resultSet, null, "region_name", null);
        settings.isTemplate = true;
        settings.templateName = resultSet.getString("template_name");
        settings.roomName = settings.templateName;
        return settings;
    }

    private static StoredRoomSettings readSettings(ResultSet resultSet, String roomColumn, String regionColumn, String timelineColumn) throws SQLException {
        StoredRoomSettings settings = new StoredRoomSettings();
        if(roomColumn != null) {
            settings.roomName = resultSet.getString(roomColumn);
        }
        settings.regionName = resultSet.getString(regionColumn);
        settings.region = Region.fromStoredName(settings.regionName);
        if(timelineColumn != null) {
            settings.timeline = timelineFromKey(resultSet.getString(timelineColumn));
        }
        settings.filePath = resultSet.getString("file_path");

        settings.parentTemplate = resultSet.getString("parent_template");
        settings.connections.addAll(decodeStringList(resultSet.getString("connections")));
        settings.tags.addAll(decodeStringList(resultSet.getString("tags")).stream().map(RoomTag::fromString).filter(t -> t != RoomTag.UNKNOWN).toList());
        settings.roomAttrs.addAll(RegionPropertiesParser.decodeRoomAttrs(resultSet.getString("room_attrs")));

        String dangerType = resultSet.getString("danger_type");
        if(dangerType != null) {
            settings.dangerType = DangerType.fromValue(dangerType);
        }

        settings.rainIntensity = readNullableFloat(resultSet, "rain_intensity");
        settings.rumbleIntensity = readNullableFloat(resultSet, "rumble_intensity");
        settings.ceilingDrips = readNullableFloat(resultSet, "ceiling_drips");
        settings.waveSpeed = readNullableFloat(resultSet, "wave_speed");
        settings.waveAmplitude = readNullableFloat(resultSet, "wave_amplitude");
        settings.waveLength = readNullableFloat(resultSet, "wave_length");
        settings.secondWaveAmplitude = readNullableFloat(resultSet, "second_wave_amplitude");
        settings.secondWaveLength = readNullableFloat(resultSet, "second_wave_length");
        settings.clouds = readNullableFloat(resultSet, "clouds");
        settings.grime = readNullableFloat(resultSet, "grime");
        settings.bkgDroneVolume = readNullableFloat(resultSet, "bkg_drone_volume");
        settings.bkgDroneNoThreatVolume = readNullableFloat(resultSet, "bkg_drone_no_threat_volume");
        settings.randomItemDensity = readNullableFloat(resultSet, "random_item_density");
        settings.randomItemSpearChance = readNullableFloat(resultSet, "random_item_spear_chance");
        settings.waterReflectionAlpha = readNullableFloat(resultSet, "water_reflection_alpha");
        settings.palette = readNullableInt(resultSet, "palette");
        settings.effectColorA = readNullableInt(resultSet, "effect_color_a");
        settings.effectColorB = readNullableInt(resultSet, "effect_color_b");
        settings.terrainLight = readNullableFloat(resultSet, "terrain_light");
        settings.terrainStainAmount = readNullableFloat(resultSet, "terrain_stain_amount");
        settings.terrainStainBrightness = readNullableFloat(resultSet, "terrain_stain_brightness");
        settings.terrainStainHeight = readNullableFloat(resultSet, "terrain_stain_height");
        settings.terrainWaves = readNullableFloat(resultSet, "terrain_waves");
        settings.terrainEdgeRadius = readNullableFloat(resultSet, "terrain_edge_radius");
        settings.terrainGooHeight = readNullableFloat(resultSet, "terrain_goo_height");
        settings.terrainGrain = readNullableFloat(resultSet, "terrain_grain");
        settings.terrainDepth = readNullableFloat(resultSet, "terrain_depth");
        settings.terrainSkyFade = readNullableFloat(resultSet, "terrain_sky_fade");
        settings.terrainPalette = resultSet.getString("terrain_palette");

        int roomSpecificScript = resultSet.getInt("room_specific_script");
        if(!resultSet.wasNull()) {
            settings.roomSpecificScript = roomSpecificScript != 0;
        }
        int wetTerrain = resultSet.getInt("wet_terrain");
        if(!resultSet.wasNull()) {
            settings.wetTerrain = wetTerrain != 0;
        }

        int fadePaletteIndex = resultSet.getInt("fade_palette_index");
        if(!resultSet.wasNull()) {
            settings.fadePalette = new FadePalette(
                    fadePaletteIndex,
                    decodeFloatArray(resultSet.getString("fade_palette_values")));
        }

        String terrainFadePaletteName = resultSet.getString("terrain_fade_palette_name");
        if(terrainFadePaletteName != null) {
            settings.terrainFadePalette = new TerrainFadePalette(
                    terrainFadePaletteName,
                    decodeFloatArray(resultSet.getString("terrain_fade_palette_values")));
        }
        return settings;
    }

    private static Float readNullableFloat(ResultSet resultSet, String column) throws SQLException {
        float value = resultSet.getFloat(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer readNullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static boolean readBoolean(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getInt(column) != 0;
    }

    private int bindSettings(PreparedStatement statement, int startIndex, StoredRoomSettings settings, boolean template) throws SQLException {
        int index = startIndex;
        index = bindStringField(statement, index, RoomSettingsField.FILE_PATH, settings.filePath);
        index = bindStringField(statement, index, RoomSettingsField.PARENT_TEMPLATE, settings.parentTemplate);
        statement.setString(index++, encodeStringList(settings.connections));
        List<String> tagNames = settings.tags.stream().filter(java.util.Objects::nonNull).map(RoomTag::name).toList();
        statement.setString(index++, encodeStringList(tagNames));
        statement.setString(index++, RegionPropertiesParser.encodeRoomAttrs(settings.roomAttrs));
        if(includes(RoomSettingsField.DANGER_TYPE) && settings.dangerType != null) {
            statement.setString(index++, settings.dangerType.getValue());
        }
        else {
            statement.setNull(index++, java.sql.Types.VARCHAR);
        }
        index = bindFloatField(statement, index, RoomSettingsField.RAIN_INTENSITY, settings.rainIntensity);
        index = bindFloatField(statement, index, RoomSettingsField.RUMBLE_INTENSITY, settings.rumbleIntensity);
        index = bindFloatField(statement, index, RoomSettingsField.CEILING_DRIPS, settings.ceilingDrips);
        index = bindFloatField(statement, index, RoomSettingsField.WAVE_SPEED, settings.waveSpeed);
        index = bindFloatField(statement, index, RoomSettingsField.WAVE_AMPLITUDE, settings.waveAmplitude);
        index = bindFloatField(statement, index, RoomSettingsField.WAVE_LENGTH, settings.waveLength);
        index = bindFloatField(statement, index, RoomSettingsField.SECOND_WAVE_AMPLITUDE, settings.secondWaveAmplitude);
        index = bindFloatField(statement, index, RoomSettingsField.SECOND_WAVE_LENGTH, settings.secondWaveLength);
        index = bindFloatField(statement, index, RoomSettingsField.CLOUDS, settings.clouds);
        index = bindFloatField(statement, index, RoomSettingsField.GRIME, settings.grime);
        index = bindFloatField(statement, index, RoomSettingsField.BKG_DRONE_VOLUME, settings.bkgDroneVolume);
        index = bindFloatField(statement, index, RoomSettingsField.BKG_DRONE_NO_THREAT_VOLUME, settings.bkgDroneNoThreatVolume);
        index = bindFloatField(statement, index, RoomSettingsField.RANDOM_ITEM_DENSITY, settings.randomItemDensity);
        index = bindFloatField(statement, index, RoomSettingsField.RANDOM_ITEM_SPEAR_CHANCE, settings.randomItemSpearChance);
        index = bindFloatField(statement, index, RoomSettingsField.WATER_REFLECTION_ALPHA, settings.waterReflectionAlpha);
        index = bindIntField(statement, index, RoomSettingsField.PALETTE, settings.palette);
        index = bindIntField(statement, index, RoomSettingsField.EFFECT_COLOR_A, settings.effectColorA);
        index = bindIntField(statement, index, RoomSettingsField.EFFECT_COLOR_B, settings.effectColorB);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_LIGHT, settings.terrainLight);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_STAIN_AMOUNT, settings.terrainStainAmount);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_STAIN_BRIGHTNESS, settings.terrainStainBrightness);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_STAIN_HEIGHT, settings.terrainStainHeight);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_WAVES, settings.terrainWaves);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_EDGE_RADIUS, settings.terrainEdgeRadius);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_GOO_HEIGHT, settings.terrainGooHeight);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_GRAIN, settings.terrainGrain);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_DEPTH, settings.terrainDepth);
        index = bindFloatField(statement, index, RoomSettingsField.TERRAIN_SKY_FADE, settings.terrainSkyFade);
        index = bindStringField(statement, index, RoomSettingsField.TERRAIN_PALETTE, settings.terrainPalette);
        index = bindBooleanField(statement, index, RoomSettingsField.ROOM_SPECIFIC_SCRIPT, settings.roomSpecificScript);
        index = bindBooleanField(statement, index, RoomSettingsField.WET_TERRAIN, settings.wetTerrain);

        if(includes(RoomSettingsField.FADE_PALETTE) && settings.fadePalette != null) {
            statement.setInt(index++, settings.fadePalette.palette);
            statement.setString(index++, encodeFloatArray(settings.fadePalette.fades));
        }
        else {
            statement.setNull(index++, java.sql.Types.INTEGER);
            statement.setNull(index++, java.sql.Types.VARCHAR);
        }

        if(includes(RoomSettingsField.TERRAIN_FADE_PALETTE) && settings.terrainFadePalette != null) {
            statement.setString(index++, settings.terrainFadePalette.palette);
            statement.setString(index++, encodeFloatArray(settings.terrainFadePalette.fades));
        }
        else {
            statement.setNull(index++, java.sql.Types.VARCHAR);
            statement.setNull(index++, java.sql.Types.VARCHAR);
        }
        return index;
    }

    private boolean includes(RoomSettingsField field) {
        return settings.includes(field);
    }

    private int bindStringField(PreparedStatement statement, int index, RoomSettingsField field, String value) throws SQLException {
        if(includes(field)) {
            setNullableString(statement, index++, value);
        }
        else {
            statement.setNull(index++, java.sql.Types.VARCHAR);
        }
        return index;
    }

    private int bindFloatField(PreparedStatement statement, int index, RoomSettingsField field, Float value) throws SQLException {
        if(includes(field)) {
            setNullableFloat(statement, index++, value);
        }
        else {
            statement.setNull(index++, java.sql.Types.REAL);
        }
        return index;
    }

    private int bindIntField(PreparedStatement statement, int index, RoomSettingsField field, Integer value) throws SQLException {
        if(includes(field)) {
            setNullableInt(statement, index++, value);
        }
        else {
            statement.setNull(index++, java.sql.Types.INTEGER);
        }
        return index;
    }

    private int bindBooleanField(PreparedStatement statement, int index, RoomSettingsField field, Boolean value) throws SQLException {
        if(includes(field)) {
            setNullableBoolean(statement, index++, value);
        }
        else {
            statement.setNull(index++, java.sql.Types.INTEGER);
        }
        return index;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if(value != null) {
            statement.setString(index, value);
        }
        else {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
    }

    private static void setNullableFloat(PreparedStatement statement, int index, Float value) throws SQLException {
        if(value != null) {
            statement.setFloat(index, value);
        }
        else {
            statement.setNull(index, java.sql.Types.REAL);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if(value != null) {
            statement.setInt(index, value);
        }
        else {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if(value != null) {
            statement.setBoolean(index, value);
        }
        else {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private void saveEffects(EffectOwner owner, StoredRoomSettings settings, boolean template) throws SQLException {
        deleteEffects(owner);
        if(!this.settings.includes(RoomSettingsField.EFFECTS) || settings.effects.isEmpty()) {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO room_setting_effects(
                    owner_kind, region_name, room_name, template_name, timeline,
                    effect_index, effect_type, amount, extra_amounts,
                    panel_x, panel_y, inherited, over_write, unrecognized_attributes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for(int i = 0; i < settings.effects.size(); i++) {
                RoomEffect effect = settings.effects.get(i);
                int index = 1;
                statement.setString(index++, owner.kind);
                statement.setString(index++, owner.regionName);
                statement.setString(index++, owner.roomName);
                statement.setString(index++, owner.templateName);
                statement.setString(index++, owner.timeline);
                statement.setInt(index++, i);
                statement.setString(index++, effect.typeName());
                statement.setFloat(index++, effect.amount);
                statement.setString(index++, encodeFloatArray(effect.extraAmounts));
                statement.setDouble(index++, effect.panelPosition.x);
                statement.setDouble(index++, effect.panelPosition.y);
                statement.setBoolean(index++, effect.inherited);
                statement.setBoolean(index++, effect.overWrite);
                statement.setString(index, encodeStringArray(effect.unrecognizedAttributes));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void loadEffects(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT effect_type, amount, extra_amounts, panel_x, panel_y, inherited, over_write, unrecognized_attributes
                FROM room_setting_effects
                WHERE owner_kind = ? AND region_name = ? AND room_name = ? AND template_name = ? AND timeline = ?
                ORDER BY effect_index
                """)) {
            statement.setString(1, owner.kind);
            statement.setString(2, owner.regionName);
            statement.setString(3, owner.roomName);
            statement.setString(4, owner.templateName);
            statement.setString(5, owner.timeline);
            try(ResultSet resultSet = statement.executeQuery()) {
                while(resultSet.next()) {
                    String stored = resultSet.getString("effect_type");
                    RoomEffect.Type type = RoomEffect.Type.fromValue(stored);
                    RoomEffect effect = new RoomEffect(type, resultSet.getFloat("amount"), readBoolean(resultSet, "inherited"));
                    if (type == RoomEffect.Type.UNKNOWN) {
                        effect.rawType = stored;
                    }

                    effect.overWrite = readBoolean(resultSet, "over_write");
                    effect.panelPosition.set(resultSet.getDouble("panel_x"), resultSet.getDouble("panel_y"));
                    effect.extraAmounts = decodeFloatArray(resultSet.getString("extra_amounts"));
                    effect.unrecognizedAttributes = decodeStringArray(resultSet.getString("unrecognized_attributes"));
                    settings.effects.add(effect);
                }
            }
        }
    }

    private void deleteEffects(EffectOwner owner) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM room_setting_effects
                WHERE owner_kind = ? AND region_name = ? AND room_name = ? AND template_name = ? AND timeline = ?
                """)) {
            statement.setString(1, owner.kind);
            statement.setString(2, owner.regionName);
            statement.setString(3, owner.roomName);
            statement.setString(4, owner.templateName);
            statement.setString(5, owner.timeline);
            statement.executeUpdate();
        }
    }

    private void saveAmbientSounds(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        deleteOwnedRows("room_ambient_sounds", owner);
        if(!this.settings.includes(RoomSettingsField.AMBIENT_SOUNDS) || settings.ambientSounds.isEmpty()) {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO room_ambient_sounds(
                    owner_kind, region_name, room_name, template_name, timeline,
                    sound_index, sound_type, sample, volume, pitch, inherited, over_write,
                    panel_x, panel_y, doppler_fac, direction_degrees,
                    pos_x, pos_y, rad_handle_x, rad_handle_y, rad, taper
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for(int i = 0; i < settings.ambientSounds.size(); i++) {
                AmbientSound sound = settings.ambientSounds.get(i);
                int idx = 1;
                statement.setString(idx++, owner.kind);
                statement.setString(idx++, owner.regionName);
                statement.setString(idx++, owner.roomName);
                statement.setString(idx++, owner.templateName);
                statement.setString(idx++, owner.timeline);
                statement.setInt(idx++, i);
                statement.setString(idx++, sound.type.name());
                statement.setString(idx++, sound.sample);
                statement.setFloat(idx++, sound.volume);
                statement.setFloat(idx++, sound.pitch);
                statement.setBoolean(idx++, sound.inherited);
                statement.setBoolean(idx++, sound.overWrite);
                statement.setDouble(idx++, sound.panelPositionX);
                statement.setDouble(idx++, sound.panelPositionY);
                if(sound instanceof DirectionalSound dir) {
                    statement.setFloat(idx++, dir.dopplerFac);
                    statement.setFloat(idx++, dir.directionDegrees);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx, java.sql.Types.REAL);
                } else if(sound instanceof SpotSound spot) {
                    statement.setFloat(idx++, spot.dopplerFac);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setDouble(idx++, spot.posX);
                    statement.setDouble(idx++, spot.posY);
                    statement.setDouble(idx++, spot.radHandlePosX);
                    statement.setDouble(idx++, spot.radHandlePosY);
                    statement.setFloat(idx++, spot.rad);
                    statement.setFloat(idx, spot.taper);
                } else {
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx, java.sql.Types.REAL);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void loadAmbientSounds(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT sound_type, sample, volume, pitch, inherited, over_write,
                       panel_x, panel_y, doppler_fac, direction_degrees,
                       pos_x, pos_y, rad_handle_x, rad_handle_y, rad, taper
                FROM room_ambient_sounds
                WHERE owner_kind = ? AND region_name = ? AND room_name = ? AND template_name = ? AND timeline = ?
                ORDER BY sound_index
                """)) {
            bindOwner(statement, owner);
            try(ResultSet rs = statement.executeQuery()) {
                while(rs.next()) {
                    AmbientSound.Type soundType = AmbientSound.Type.valueOf(rs.getString("sound_type"));
                    AmbientSound sound;
                    switch(soundType) {
                        case DIRECTIONAL -> {
                            DirectionalSound dir = new DirectionalSound();
                            dir.dopplerFac = rs.getFloat("doppler_fac");
                            dir.directionDegrees = rs.getFloat("direction_degrees");
                            sound = dir;
                        }
                        case SPOT -> {
                            SpotSound spot = new SpotSound();
                            spot.dopplerFac = rs.getFloat("doppler_fac");
                            spot.posX = rs.getDouble("pos_x");
                            spot.posY = rs.getDouble("pos_y");
                            spot.radHandlePosX = rs.getDouble("rad_handle_x");
                            spot.radHandlePosY = rs.getDouble("rad_handle_y");
                            spot.rad = rs.getFloat("rad");
                            spot.taper = rs.getFloat("taper");
                            sound = spot;
                        }
                        default -> sound = new OmniDirectionalSound();
                    }
                    sound.sample = rs.getString("sample");
                    sound.volume = rs.getFloat("volume");
                    sound.pitch = rs.getFloat("pitch");
                    sound.inherited = readBoolean(rs, "inherited");
                    sound.overWrite = readBoolean(rs, "over_write");
                    sound.panelPositionX = rs.getDouble("panel_x");
                    sound.panelPositionY = rs.getDouble("panel_y");
                    settings.ambientSounds.add(sound);
                }
            }
        }
        catch(SQLException exception) {
            if(!isMissingTable(exception)) {
                throw exception;
            }
        }
    }

    private void saveTriggers(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        deleteOwnedRows("room_triggers", owner);
        if(!this.settings.includes(RoomSettingsField.TRIGGERS) || settings.triggers.isEmpty()) {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO room_triggers(
                    owner_kind, region_name, room_name, template_name, timeline,
                    trigger_index, trigger_type, active_to_cycle, active_from_cycle, delay,
                    multi_use, fire_chance, entrance, karma, slugcats, panel_x, panel_y,
                    spot_pos_x, spot_pos_y, spot_rad_handle_x, spot_rad_handle_y, spot_rad,
                    event_type, ev_song_name, ev_prio, ev_volume, ev_max_threat_level,
                    ev_drone_tolerance, ev_fade_in_time, ev_loop, ev_one_song_per_cycle,
                    ev_stop_at_death, ev_stop_at_gate, ev_rooms_range, ev_cycles_rest,
                    ev_fade_out_time, ev_stop_type,
                    ev_after_encounter, ev_only_when_showing_direction, ev_from_cycle
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for(int i = 0; i < settings.triggers.size(); i++) {
                EventTrigger trigger = settings.triggers.get(i);
                int idx = 1;
                statement.setString(idx++, owner.kind);
                statement.setString(idx++, owner.regionName);
                statement.setString(idx++, owner.roomName);
                statement.setString(idx++, owner.templateName);
                statement.setString(idx++, owner.timeline);
                statement.setInt(idx++, i);
                statement.setString(idx++, trigger.triggerType.name());
                statement.setInt(idx++, trigger.activeToCycle);
                statement.setInt(idx++, trigger.activeFromCycle);
                statement.setInt(idx++, trigger.delay);
                statement.setBoolean(idx++, trigger.multiUse);
                statement.setFloat(idx++, trigger.fireChance);
                statement.setInt(idx++, trigger.entrance);
                statement.setInt(idx++, trigger.karma);
                statement.setString(idx++, encodeStringList(trigger.slugcats));
                statement.setDouble(idx++, trigger.panelPositionX);
                statement.setDouble(idx++, trigger.panelPositionY);
                if(trigger instanceof SpotTrigger spot) {
                    statement.setDouble(idx++, spot.posX);
                    statement.setDouble(idx++, spot.posY);
                    statement.setDouble(idx++, spot.radHandlePosX);
                    statement.setDouble(idx++, spot.radHandlePosY);
                    statement.setFloat(idx++, spot.rad);
                } else {
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                }
                TriggeredEvent ev = trigger.tEvent;
                if(ev instanceof MusicEvent music) {
                    statement.setString(idx++, music.eventType.name());
                    statement.setString(idx++, music.songName);
                    statement.setFloat(idx++, music.prio);
                    statement.setFloat(idx++, music.volume);
                    statement.setFloat(idx++, music.maxThreatLevel);
                    statement.setFloat(idx++, music.droneTolerance);
                    statement.setFloat(idx++, music.fadeInTime);
                    statement.setBoolean(idx++, music.loop);
                    statement.setBoolean(idx++, music.oneSongPerCycle);
                    statement.setBoolean(idx++, music.stopAtDeath);
                    statement.setBoolean(idx++, music.stopAtGate);
                    statement.setInt(idx++, music.roomsRange);
                    statement.setInt(idx++, music.cyclesRest);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.VARCHAR);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx, java.sql.Types.INTEGER);
                } else if(ev instanceof StopMusicEvent stop) {
                    statement.setString(idx++, stop.eventType.name());
                    statement.setString(idx++, stop.songName);
                    statement.setFloat(idx++, stop.prio);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setFloat(idx++, stop.fadeOutTime);
                    statement.setString(idx++, stop.stopType);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx, java.sql.Types.INTEGER);
                } else if(ev instanceof ShowProjectedImageEvent proj) {
                    statement.setString(idx++, proj.eventType.name());
                    statement.setNull(idx++, java.sql.Types.VARCHAR);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.VARCHAR);
                    statement.setBoolean(idx++, proj.afterEncounter);
                    statement.setBoolean(idx++, proj.onlyWhenShowingDirection);
                    statement.setInt(idx, proj.fromCycle);
                } else {
                    statement.setNull(idx++, java.sql.Types.VARCHAR);
                    statement.setNull(idx++, java.sql.Types.VARCHAR);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.REAL);
                    statement.setNull(idx++, java.sql.Types.VARCHAR);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx++, java.sql.Types.INTEGER);
                    statement.setNull(idx, java.sql.Types.INTEGER);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void loadTriggers(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT trigger_type, active_to_cycle, active_from_cycle, delay, multi_use, fire_chance,
                       entrance, karma, slugcats, panel_x, panel_y,
                       spot_pos_x, spot_pos_y, spot_rad_handle_x, spot_rad_handle_y, spot_rad,
                       event_type, ev_song_name, ev_prio, ev_volume, ev_max_threat_level,
                       ev_drone_tolerance, ev_fade_in_time, ev_loop, ev_one_song_per_cycle,
                       ev_stop_at_death, ev_stop_at_gate, ev_rooms_range, ev_cycles_rest,
                       ev_fade_out_time, ev_stop_type,
                       ev_after_encounter, ev_only_when_showing_direction, ev_from_cycle
                FROM room_triggers
                WHERE owner_kind = ? AND region_name = ? AND room_name = ? AND template_name = ? AND timeline = ?
                ORDER BY trigger_index
                """)) {
            bindOwner(statement, owner);
            try(ResultSet rs = statement.executeQuery()) {
                while(rs.next()) {
                    EventTrigger.TriggerType triggerType = EventTrigger.TriggerType.valueOf(rs.getString("trigger_type"));
                    EventTrigger trigger;
                    if(triggerType == EventTrigger.TriggerType.SPOT) {
                        SpotTrigger spot = new SpotTrigger();
                        spot.posX = rs.getDouble("spot_pos_x");
                        spot.posY = rs.getDouble("spot_pos_y");
                        spot.radHandlePosX = rs.getDouble("spot_rad_handle_x");
                        spot.radHandlePosY = rs.getDouble("spot_rad_handle_y");
                        spot.rad = rs.getFloat("spot_rad");
                        trigger = spot;
                    } else {
                        trigger = new EventTrigger();
                        trigger.triggerType = triggerType;
                    }
                    trigger.activeToCycle = rs.getInt("active_to_cycle");
                    trigger.activeFromCycle = rs.getInt("active_from_cycle");
                    trigger.delay = rs.getInt("delay");
                    trigger.multiUse = readBoolean(rs, "multi_use");
                    trigger.fireChance = rs.getFloat("fire_chance");
                    trigger.entrance = rs.getInt("entrance");
                    trigger.karma = rs.getInt("karma");
                    trigger.slugcats = decodeStringList(rs.getString("slugcats"));
                    trigger.timelines = SlugcatAvailability.toTimelines(trigger.slugcats);
                    trigger.panelPositionX = rs.getDouble("panel_x");
                    trigger.panelPositionY = rs.getDouble("panel_y");
                    String eventTypeName = rs.getString("event_type");
                    if(eventTypeName != null) {
                        TriggeredEvent.EventType eventType = TriggeredEvent.EventType.valueOf(eventTypeName);
                        trigger.tEvent = switch(eventType) {
                            case MUSIC_EVENT -> {
                                MusicEvent music = new MusicEvent();
                                music.songName = rs.getString("ev_song_name");
                                music.prio = rs.getFloat("ev_prio");
                                music.volume = rs.getFloat("ev_volume");
                                music.maxThreatLevel = rs.getFloat("ev_max_threat_level");
                                music.droneTolerance = rs.getFloat("ev_drone_tolerance");
                                music.fadeInTime = rs.getFloat("ev_fade_in_time");
                                music.loop = readBoolean(rs, "ev_loop");
                                music.oneSongPerCycle = readBoolean(rs, "ev_one_song_per_cycle");
                                music.stopAtDeath = readBoolean(rs, "ev_stop_at_death");
                                music.stopAtGate = readBoolean(rs, "ev_stop_at_gate");
                                music.roomsRange = rs.getInt("ev_rooms_range");
                                music.cyclesRest = rs.getInt("ev_cycles_rest");
                                yield music;
                            }
                            case STOP_MUSIC_EVENT -> {
                                StopMusicEvent stop = new StopMusicEvent();
                                stop.songName = rs.getString("ev_song_name");
                                stop.prio = rs.getFloat("ev_prio");
                                stop.fadeOutTime = rs.getFloat("ev_fade_out_time");
                                stop.stopType = rs.getString("ev_stop_type");
                                yield stop;
                            }
                            case SHOW_PROJECTED_IMAGE_EVENT -> {
                                ShowProjectedImageEvent proj = new ShowProjectedImageEvent();
                                proj.afterEncounter = readBoolean(rs, "ev_after_encounter");
                                proj.onlyWhenShowingDirection = readBoolean(rs, "ev_only_when_showing_direction");
                                proj.fromCycle = rs.getInt("ev_from_cycle");
                                yield proj;
                            }
                            default -> null;
                        };
                    }
                    settings.triggers.add(trigger);
                }
            }
        }
        catch(SQLException exception) {
            if(!isMissingTable(exception)) {
                throw exception;
            }
        }
    }

    private void savePlacedObjects(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        deleteOwnedRows("room_placed_objects", owner);
        if(!this.settings.includes(RoomSettingsField.PLACED_OBJECTS) || settings.placedObjects.isEmpty()) {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO room_placed_objects(
                    owner_kind, region_name, room_name, template_name, timeline,
                    object_index, object_type, pos_x, pos_y, active, deactivated_by_warp_filter,
                    data_class, data_raw,
                    data_handle_x, data_handle_y, data_panel_x, data_panel_y,
                    data_strength, data_fade_with_sun, data_flat, data_color_type,
                    data_blink_type, data_blink_rate, data_night_light,
                    data_fixture_type, data_random_seed,
                    data_depth, data_rotation, data_length, data_brightness,
                    data_direction, data_skull_seed, data_pearls_seed,
                    data_insect_type, data_density,
                    data_item_type, data_chance,
                    data_min_regen, data_max_regen,
                    data_pearl_type, data_hidden, data_exit,
                    data_handle2_x, data_handle2_y, data_handle3_x, data_handle3_y,
                    data_from_depth, data_to_depth, data_intensity, data_noise, data_image_name, data_vertices,
                    data_token_string, data_is_blue, data_is_green, data_is_white, data_is_red, data_is_dev,
                    data_available_to_players, data_available_on_timelines,
                    data_min_ripple_level, data_max_ripple_level,
                    data_stage, data_greater_or_equal, data_post_game,
                    data_scale, data_chromatic_intensity, data_time_mult, data_l_intensity,
                    data_shape, data_noisiness, data_angle,
                    data_pos_x, data_pos_y, data_start_x, data_start_y, data_end_x, data_end_y,
                    data_permanent, data_radial, data_width, data_lifetime,
                    data_lightning_param, data_lightning_type, data_impact, data_volume, data_sound_type,
                    data_random, data_light,
                    data_sprite_num, data_speed, data_duration, data_frequency,
                    data_affect_group,
                    data_left_offset_x, data_left_offset_y, data_right_offset_x, data_right_offset_y,
                    data_back_height,
                    data_flow, data_dynamic, data_pre_delay, data_post_delay,
                    data_index,
                    data_dusk_palette, data_night_palette, data_terrain_palette,
                    data_abs_pulse, data_pulse_min, data_pulse_max, data_pulse_rate,
                    data_scale_min, data_scale_max,
                    data_interp_dur_min, data_interp_dur_max, data_interp_dist_min, data_interp_dist_max,
                    data_dir_dev_min, data_dir_dev_max, data_dir_min, data_dir_max,
                    data_color_h_min, data_color_h_max, data_color_s_min, data_color_s_max,
                    data_color_l_min, data_color_l_max,
                    data_interp_trans, data_alpha_trans, data_num_keyframes,
                    data_sprite_type, data_dir_lerp_type, data_speed_lerp_type,
                    data_glow_rad, data_glow_strength, data_rotation_rate
                ) VALUES (
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
                )
                """)) {
            for(int i = 0; i < settings.placedObjects.size(); i++) {
                PlacedObject placed = settings.placedObjects.get(i);
                PlacedObjectData d = placed.data;
                int idx = 1;
                statement.setString(idx++, owner.kind);
                statement.setString(idx++, owner.regionName);
                statement.setString(idx++, owner.roomName);
                statement.setString(idx++, owner.templateName);
                statement.setString(idx++, owner.timeline);
                statement.setInt(idx++, i);
                statement.setString(idx++, placed.type.name());
                statement.setDouble(idx++, placed.posX);
                statement.setDouble(idx++, placed.posY);
                statement.setBoolean(idx++, placed.active);
                statement.setBoolean(idx++, placed.deactivatedByWarpFilter);
                statement.setString(idx++, d != null ? d.getClass().getSimpleName() : null);
                idx = bindPlacedObjectData(statement, idx, d);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private int bindPlacedObjectData(PreparedStatement st, int idx, PlacedObjectData d) throws SQLException {
        st.setString(idx++, d instanceof PlacedObjectData.RawData raw ? raw.raw : null);
        double handleX = 0, handleY = 0, panelX = 0, panelY = 0;
        if(d instanceof PlacedObjectData.LightSourceData ld) { handleX = ld.handlePosX; handleY = ld.handlePosY; panelX = ld.panelPosX; panelY = ld.panelPosY; }
        else if(d instanceof PlacedObjectData.ResizableObjectData rd) { handleX = rd.handlePosX; handleY = rd.handlePosY; }
        else if(d instanceof PlacedObjectData.GridRectObjectData gr) { handleX = gr.handlePosX; handleY = gr.handlePosY; }
        else if(d instanceof PlacedObjectData.CollectTokenData ct) { handleX = ct.handlePosX; handleY = ct.handlePosY; panelX = ct.panelPosX; panelY = ct.panelPosY; }
        else if(d instanceof PlacedObjectData.FilterData fd) { handleX = fd.handlePosX; handleY = fd.handlePosY; panelX = fd.panelPosX; panelY = fd.panelPosY; }
        else if(d instanceof PlacedObjectData.RippleLevelFilterData rf) { handleX = rf.handlePosX; handleY = rf.handlePosY; panelX = rf.panelPosX; panelY = rf.panelPosY; }
        else if(d instanceof PlacedObjectData.PrinceFilterData pf) { handleX = pf.handlePosX; handleY = pf.handlePosY; panelX = pf.panelPosX; panelY = pf.panelPosY; }
        else if(d instanceof PlacedObjectData.CellDistortionData cd) { handleX = cd.handlePosX; handleY = cd.handlePosY; panelX = cd.panelPosX; panelY = cd.panelPosY; }
        else if(d instanceof PlacedObjectData.OEsphereData oe) { handleX = oe.handlePosX; handleY = oe.handlePosY; panelX = oe.panelPosX; panelY = oe.panelPosY; }
        else if(d instanceof PlacedObjectData.SnowSourceData ss) { handleX = ss.handlePosX; handleY = ss.handlePosY; panelX = ss.panelPosX; panelY = ss.panelPosY; }
        else if(d instanceof PlacedObjectData.LocalBlizzardData lb) { handleX = lb.handlePosX; handleY = lb.handlePosY; panelX = lb.panelPosX; panelY = lb.panelPosY; }
        else if(d instanceof PlacedObjectData.EnergySwirlData es) { handleX = es.handlePosX; handleY = es.handlePosY; panelX = es.panelPosX; panelY = es.panelPosY; }
        else if(d instanceof PlacedObjectData.SteamPipeData sp) { handleX = sp.handlePosX; handleY = sp.handlePosY; }
        else if(d instanceof PlacedObjectData.HarmfulSteamData hs) { handleX = hs.handlePosX; handleY = hs.handlePosY; panelX = hs.panelPosX; panelY = hs.panelPosY; }
        else if(d instanceof PlacedObjectData.SkyWhalePathfindingData sw) { handleX = sw.handlePosX; handleY = sw.handlePosY; panelX = sw.panelPosX; panelY = sw.panelPosY; }
        setNullableDouble(st, idx++, d != null ? handleX : null);
        setNullableDouble(st, idx++, d != null ? handleY : null);
        setNullableDouble(st, idx++, d != null ? panelX : null);
        setNullableDouble(st, idx++, d != null ? panelY : null);
        if(d instanceof PlacedObjectData.LightSourceData ld) {
            st.setFloat(idx++, ld.strength); st.setBoolean(idx++, ld.fadeWithSun); st.setBoolean(idx++, ld.flat);
            st.setString(idx++, ld.colorType); st.setString(idx++, ld.blinkType); st.setFloat(idx++, ld.blinkRate);
            st.setBoolean(idx++, ld.nightLight);
        } else { for(int n = 0; n < 7; n++) st.setNull(idx++, java.sql.Types.NULL); }
        if(d instanceof PlacedObjectData.LightFixtureData lf) {
            st.setString(idx++, lf.fixtureType); st.setInt(idx++, lf.randomSeed);
        } else { st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.INTEGER); }
        if(d instanceof PlacedObjectData.SSLightRodData lr) {
            st.setFloat(idx++, lr.depth); st.setFloat(idx++, lr.rotation); st.setFloat(idx++, lr.length); st.setFloat(idx++, lr.brightness);
        } else { for(int n = 0; n < 4; n++) st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.ScavengerOutpostData so) {
            st.setFloat(idx++, so.direction); st.setInt(idx++, so.skullSeed); st.setInt(idx++, so.pearlsSeed);
        } else { st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); }
        if(d instanceof PlacedObjectData.InsectGroupData ig) {
            st.setString(idx++, ig.insectType); st.setFloat(idx++, ig.density);
        } else { st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.MultiplayerItemData mi) {
            st.setString(idx++, mi.itemType); st.setFloat(idx++, mi.chance);
        } else { st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.ConsumableObjectData co) {
            st.setInt(idx++, co.minRegen); st.setInt(idx++, co.maxRegen);
        } else { st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); }
        if(d instanceof PlacedObjectData.DataPearlData dp) {
            st.setString(idx++, dp.pearlType); st.setBoolean(idx++, dp.hidden); st.setNull(idx++, java.sql.Types.INTEGER);
        } else if(d instanceof PlacedObjectData.VoidSpawnEggData ve) {
            st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.INTEGER); st.setInt(idx++, ve.exit);
        } else { st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); }
        if(d instanceof PlacedObjectData.QuadObjectData qd) {
            st.setDouble(idx++, qd.handle0X); st.setDouble(idx++, qd.handle0Y);
            st.setDouble(idx++, qd.handle1X); st.setDouble(idx++, qd.handle1Y);
        } else { for(int n = 0; n < 4; n++) st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.DeepProcessingData dp) {
            st.setFloat(idx++, dp.fromDepth); st.setFloat(idx++, dp.toDepth); st.setFloat(idx++, dp.intensity);
            st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.VARCHAR);
        } else if(d instanceof PlacedObjectData.CustomDecalData cd) {
            st.setFloat(idx++, cd.fromDepth); st.setFloat(idx++, cd.toDepth); st.setFloat(idx++, cd.noise);
            st.setString(idx++, cd.imageName);
            st.setString(idx++, encodeVertices(cd.vertices));
            st.setNull(idx++, java.sql.Types.VARCHAR);
        } else { for(int n = 0; n < 6; n++) st.setNull(idx++, java.sql.Types.NULL); }
        if(d instanceof PlacedObjectData.CollectTokenData ct) {
            st.setString(idx++, ct.tokenString);
            st.setBoolean(idx++, ct.isBlue); st.setBoolean(idx++, ct.isGreen); st.setBoolean(idx++, ct.isWhite);
            st.setBoolean(idx++, ct.isRed); st.setBoolean(idx++, ct.isDev);
            st.setString(idx++, encodeStringList(ct.availableToPlayers));
            st.setNull(idx++, java.sql.Types.VARCHAR);
        } else if(d instanceof PlacedObjectData.FilterData fd) {
            st.setNull(idx++, java.sql.Types.VARCHAR);
            for(int n = 0; n < 5; n++) st.setNull(idx++, java.sql.Types.INTEGER);
            st.setString(idx++, encodeStringList(fd.availableToPlayers));
            st.setString(idx++, encodeTimelines(fd.availableOnTimelines));
        } else { for(int n = 0; n < 8; n++) st.setNull(idx++, java.sql.Types.NULL); }
        if(d instanceof PlacedObjectData.RippleLevelFilterData rf) {
            st.setFloat(idx++, rf.minimumRippleLevel); st.setFloat(idx++, rf.maximumRippleLevel);
        } else { st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.PrinceFilterData pf) {
            st.setInt(idx++, pf.stage); st.setBoolean(idx++, pf.greaterOrEqual); st.setBoolean(idx++, pf.postGame);
        } else { st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); }
        if(d instanceof PlacedObjectData.CellDistortionData cd) {
            st.setFloat(idx++, cd.scale); st.setFloat(idx++, cd.chromaticIntensity); st.setFloat(idx++, cd.timeMult);
            st.setNull(idx++, java.sql.Types.REAL);
        } else if(d instanceof PlacedObjectData.LocalBlizzardData lb) {
            st.setFloat(idx++, lb.scale); st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL);
            st.setNull(idx++, java.sql.Types.REAL);
        } else if(d instanceof PlacedObjectData.OEsphereData oe) {
            st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL);
            st.setFloat(idx++, oe.lIntensity);
        } else { for(int n = 0; n < 4; n++) st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.SnowSourceData ss) {
            st.setString(idx++, ss.shape); st.setFloat(idx++, ss.noisiness); st.setNull(idx++, java.sql.Types.REAL);
        } else if(d instanceof PlacedObjectData.LocalBlizzardData lb) {
            st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.REAL); st.setFloat(idx++, lb.angle);
        } else { st.setNull(idx++, java.sql.Types.VARCHAR); st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.LightningMachineData lm) {
            st.setDouble(idx++, lm.posX); st.setDouble(idx++, lm.posY);
            st.setDouble(idx++, lm.startPointX); st.setDouble(idx++, lm.startPointY);
            st.setDouble(idx++, lm.endPointX); st.setDouble(idx++, lm.endPointY);
        } else { for(int n = 0; n < 6; n++) st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.LightningMachineData lm) {
            st.setBoolean(idx++, lm.permanent); st.setBoolean(idx++, lm.radial);
            st.setFloat(idx++, lm.width); st.setFloat(idx++, lm.lifeTime);
            st.setFloat(idx++, lm.lightningParam); st.setFloat(idx++, lm.lightningType);
            st.setInt(idx++, lm.impact); st.setFloat(idx++, lm.volume); st.setInt(idx++, lm.soundType);
            st.setBoolean(idx++, lm.random); st.setBoolean(idx++, lm.light);
        } else { for(int n = 0; n < 11; n++) st.setNull(idx++, java.sql.Types.NULL); }
        if(d instanceof PlacedObjectData.AdjustableFanData af) {
            st.setInt(idx++, af.spriteNum); st.setFloat(idx++, af.speed);
            st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL);
        } else if(d instanceof PlacedObjectData.HugeTurbineData ht) {
            st.setNull(idx++, java.sql.Types.INTEGER); st.setFloat(idx++, ht.speed);
            st.setNull(idx++, java.sql.Types.REAL); st.setNull(idx++, java.sql.Types.REAL);
        } else if(d instanceof PlacedObjectData.HarmfulSteamData hs) {
            st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.REAL);
            st.setFloat(idx++, hs.duration); st.setFloat(idx++, hs.frequency);
        } else { for(int n = 0; n < 4; n++) st.setNull(idx++, java.sql.Types.NULL); }
        if(d instanceof PlacedObjectData.WindData wd) {
            st.setString(idx++, wd.affectGroup);
        } else { st.setNull(idx++, java.sql.Types.VARCHAR); }
        if(d instanceof PlacedObjectData.TerrainHandleData th) {
            st.setDouble(idx++, th.leftOffsetX); st.setDouble(idx++, th.leftOffsetY);
            st.setDouble(idx++, th.rightOffsetX); st.setDouble(idx++, th.rightOffsetY);
            st.setFloat(idx++, th.backHeight);
        } else { for(int n = 0; n < 5; n++) st.setNull(idx++, java.sql.Types.REAL); }
        if(d instanceof PlacedObjectData.WaterFlowData wf) {
            st.setFloat(idx++, wf.flow); st.setBoolean(idx++, wf.dynamic);
            st.setFloat(idx++, wf.preDelay); st.setFloat(idx++, wf.postDelay);
        } else { for(int n = 0; n < 4; n++) st.setNull(idx++, java.sql.Types.NULL); }
        if(d instanceof PlacedObjectData.SkyWhalePathfindingData sw) {
            st.setInt(idx++, sw.index);
        } else { st.setNull(idx++, java.sql.Types.INTEGER); }
        if(d instanceof PlacedObjectData.DayNightData dn) {
            st.setInt(idx++, dn.duskPalette); st.setInt(idx++, dn.nightPalette); st.setNull(idx++, java.sql.Types.VARCHAR);
        } else if(d instanceof PlacedObjectData.AdjustableFanData af) {
            st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.VARCHAR);
        } else { st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.INTEGER); st.setNull(idx++, java.sql.Types.VARCHAR); }
        if(d instanceof PlacedObjectData.FairyParticleData fp) {
            st.setBoolean(idx++, fp.absPulse); st.setFloat(idx++, fp.pulseMin); st.setFloat(idx++, fp.pulseMax);
            st.setFloat(idx++, fp.pulseRate); st.setFloat(idx++, fp.scaleMin); st.setFloat(idx++, fp.scaleMax);
            st.setFloat(idx++, fp.interpDurMin); st.setFloat(idx++, fp.interpDurMax);
            st.setFloat(idx++, fp.interpDistMin); st.setFloat(idx++, fp.interpDistMax);
            st.setFloat(idx++, fp.dirDevMin); st.setFloat(idx++, fp.dirDevMax);
            st.setFloat(idx++, fp.dirMin); st.setFloat(idx++, fp.dirMax);
            st.setFloat(idx++, fp.colorHmin); st.setFloat(idx++, fp.colorHmax);
            st.setFloat(idx++, fp.colorSmin); st.setFloat(idx++, fp.colorSmax);
            st.setFloat(idx++, fp.colorLmin); st.setFloat(idx++, fp.colorLmax);
            st.setFloat(idx++, fp.interpTrans); st.setFloat(idx++, fp.alphaTrans); st.setFloat(idx++, fp.numKeyframes);
            st.setString(idx++, fp.spriteType); st.setString(idx++, fp.dirLerpType); st.setString(idx++, fp.speedLerpType);
            st.setFloat(idx++, fp.glowRad); st.setFloat(idx++, fp.glowStrength); st.setFloat(idx++, fp.rotationRate);
        } else { for(int n = 0; n < 29; n++) st.setNull(idx++, java.sql.Types.NULL); }
        return idx;
    }

    private void loadPlacedObjects(EffectOwner owner, StoredRoomSettings settings) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement("""
                SELECT object_type, pos_x, pos_y, active, deactivated_by_warp_filter,
                       data_class, data_raw,
                       data_handle_x, data_handle_y, data_panel_x, data_panel_y,
                       data_strength, data_fade_with_sun, data_flat, data_color_type,
                       data_blink_type, data_blink_rate, data_night_light,
                       data_fixture_type, data_random_seed,
                       data_depth, data_rotation, data_length, data_brightness,
                       data_direction, data_skull_seed, data_pearls_seed,
                       data_insect_type, data_density, data_item_type, data_chance,
                       data_min_regen, data_max_regen, data_pearl_type, data_hidden, data_exit,
                       data_handle2_x, data_handle2_y, data_handle3_x, data_handle3_y,
                       data_from_depth, data_to_depth, data_intensity, data_noise, data_image_name, data_vertices,
                       data_token_string, data_is_blue, data_is_green, data_is_white, data_is_red, data_is_dev,
                       data_available_to_players, data_available_on_timelines,
                       data_min_ripple_level, data_max_ripple_level,
                       data_stage, data_greater_or_equal, data_post_game,
                       data_scale, data_chromatic_intensity, data_time_mult, data_l_intensity,
                       data_shape, data_noisiness, data_angle,
                       data_pos_x, data_pos_y, data_start_x, data_start_y, data_end_x, data_end_y,
                       data_permanent, data_radial, data_width, data_lifetime,
                       data_lightning_param, data_lightning_type, data_impact, data_volume, data_sound_type,
                       data_random, data_light,
                       data_sprite_num, data_speed, data_duration, data_frequency,
                       data_affect_group,
                       data_left_offset_x, data_left_offset_y, data_right_offset_x, data_right_offset_y,
                       data_back_height,
                       data_flow, data_dynamic, data_pre_delay, data_post_delay,
                       data_index,
                       data_dusk_palette, data_night_palette,
                       data_abs_pulse, data_pulse_min, data_pulse_max, data_pulse_rate,
                       data_scale_min, data_scale_max,
                       data_interp_dur_min, data_interp_dur_max, data_interp_dist_min, data_interp_dist_max,
                       data_dir_dev_min, data_dir_dev_max, data_dir_min, data_dir_max,
                       data_color_h_min, data_color_h_max, data_color_s_min, data_color_s_max,
                       data_color_l_min, data_color_l_max,
                       data_interp_trans, data_alpha_trans, data_num_keyframes,
                       data_sprite_type, data_dir_lerp_type, data_speed_lerp_type,
                       data_glow_rad, data_glow_strength, data_rotation_rate
                FROM room_placed_objects
                WHERE owner_kind = ? AND region_name = ? AND room_name = ? AND template_name = ? AND timeline = ?
                ORDER BY object_index
                """)) {
            bindOwner(statement, owner);
            try(ResultSet rs = statement.executeQuery()) {
                while(rs.next()) {
                    PlacedObject placed = new PlacedObject();
                    placed.type = PlacedObject.Type.valueOf(rs.getString("object_type"));
                    placed.posX = rs.getDouble("pos_x");
                    placed.posY = rs.getDouble("pos_y");
                    placed.active = readBoolean(rs, "active");
                    placed.deactivatedByWarpFilter = readBoolean(rs, "deactivated_by_warp_filter");
                    placed.data = readPlacedObjectData(rs);
                    settings.placedObjects.add(placed);
                }
            }
        }
        catch(SQLException exception) {
            if(!isMissingTable(exception)) {
                throw exception;
            }
        }
    }

    private PlacedObjectData readPlacedObjectData(ResultSet rs) throws SQLException {
        String dataClass = rs.getString("data_class");
        if(dataClass == null) return null;
        return switch(dataClass) {
            case "RawData" -> {
                PlacedObjectData.RawData d = new PlacedObjectData.RawData();
                d.raw = rs.getString("data_raw");
                yield d;
            }
            case "LightSourceData" -> {
                PlacedObjectData.LightSourceData d = new PlacedObjectData.LightSourceData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.strength = rs.getFloat("data_strength"); d.fadeWithSun = readBoolean(rs, "data_fade_with_sun");
                d.flat = readBoolean(rs, "data_flat"); d.colorType = rs.getString("data_color_type");
                d.blinkType = rs.getString("data_blink_type"); d.blinkRate = rs.getFloat("data_blink_rate");
                d.nightLight = readBoolean(rs, "data_night_light");
                yield d;
            }
            case "LightFixtureData" -> {
                PlacedObjectData.LightFixtureData d = new PlacedObjectData.LightFixtureData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.fixtureType = rs.getString("data_fixture_type"); d.randomSeed = rs.getInt("data_random_seed");
                yield d;
            }
            case "SSLightRodData" -> {
                PlacedObjectData.SSLightRodData d = new PlacedObjectData.SSLightRodData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.depth = rs.getFloat("data_depth"); d.rotation = rs.getFloat("data_rotation");
                d.length = rs.getFloat("data_length"); d.brightness = rs.getFloat("data_brightness");
                yield d;
            }
            case "ResizableObjectData" -> {
                PlacedObjectData.ResizableObjectData d = new PlacedObjectData.ResizableObjectData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                yield d;
            }
            case "ScavengerOutpostData" -> {
                PlacedObjectData.ScavengerOutpostData d = new PlacedObjectData.ScavengerOutpostData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.direction = rs.getFloat("data_direction"); d.skullSeed = rs.getInt("data_skull_seed");
                d.pearlsSeed = rs.getInt("data_pearls_seed");
                yield d;
            }
            case "InsectGroupData" -> {
                PlacedObjectData.InsectGroupData d = new PlacedObjectData.InsectGroupData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.insectType = rs.getString("data_insect_type"); d.density = rs.getFloat("data_density");
                yield d;
            }
            case "MultiplayerItemData" -> {
                PlacedObjectData.MultiplayerItemData d = new PlacedObjectData.MultiplayerItemData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.itemType = rs.getString("data_item_type"); d.chance = rs.getFloat("data_chance");
                yield d;
            }
            case "ConsumableObjectData" -> {
                PlacedObjectData.ConsumableObjectData d = new PlacedObjectData.ConsumableObjectData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.minRegen = rs.getInt("data_min_regen"); d.maxRegen = rs.getInt("data_max_regen");
                yield d;
            }
            case "DataPearlData" -> {
                PlacedObjectData.DataPearlData d = new PlacedObjectData.DataPearlData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.minRegen = rs.getInt("data_min_regen"); d.maxRegen = rs.getInt("data_max_regen");
                d.pearlType = rs.getString("data_pearl_type"); d.hidden = readBoolean(rs, "data_hidden");
                yield d;
            }
            case "VoidSpawnEggData" -> {
                PlacedObjectData.VoidSpawnEggData d = new PlacedObjectData.VoidSpawnEggData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.minRegen = rs.getInt("data_min_regen"); d.maxRegen = rs.getInt("data_max_regen");
                d.exit = rs.getInt("data_exit");
                yield d;
            }
            case "GridRectObjectData" -> {
                PlacedObjectData.GridRectObjectData d = new PlacedObjectData.GridRectObjectData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                yield d;
            }
            case "QuadObjectData" -> {
                PlacedObjectData.QuadObjectData d = new PlacedObjectData.QuadObjectData();
                d.handle0X = rs.getDouble("data_handle_x"); d.handle0Y = rs.getDouble("data_handle_y");
                d.handle1X = rs.getDouble("data_handle2_x"); d.handle1Y = rs.getDouble("data_handle2_y");
                d.handle2X = rs.getDouble("data_handle3_x"); d.handle2Y = rs.getDouble("data_handle3_y");
                yield d;
            }
            case "DeepProcessingData" -> {
                PlacedObjectData.DeepProcessingData d = new PlacedObjectData.DeepProcessingData();
                d.handle0X = rs.getDouble("data_handle_x"); d.handle0Y = rs.getDouble("data_handle_y");
                d.handle1X = rs.getDouble("data_handle2_x"); d.handle1Y = rs.getDouble("data_handle2_y");
                d.handle2X = rs.getDouble("data_handle3_x"); d.handle2Y = rs.getDouble("data_handle3_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.fromDepth = rs.getFloat("data_from_depth"); d.toDepth = rs.getFloat("data_to_depth");
                d.intensity = rs.getFloat("data_intensity");
                yield d;
            }
            case "CustomDecalData" -> {
                PlacedObjectData.CustomDecalData d = new PlacedObjectData.CustomDecalData();
                d.handle0X = rs.getDouble("data_handle_x"); d.handle0Y = rs.getDouble("data_handle_y");
                d.handle1X = rs.getDouble("data_handle2_x"); d.handle1Y = rs.getDouble("data_handle2_y");
                d.handle2X = rs.getDouble("data_handle3_x"); d.handle2Y = rs.getDouble("data_handle3_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.fromDepth = rs.getFloat("data_from_depth"); d.toDepth = rs.getFloat("data_to_depth");
                d.noise = rs.getFloat("data_noise"); d.imageName = rs.getString("data_image_name");
                d.vertices = decodeVertices(rs.getString("data_vertices"));
                yield d;
            }
            case "CollectTokenData" -> {
                PlacedObjectData.CollectTokenData d = new PlacedObjectData.CollectTokenData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.tokenString = rs.getString("data_token_string");
                d.isBlue = readBoolean(rs, "data_is_blue"); d.isGreen = readBoolean(rs, "data_is_green");
                d.isWhite = readBoolean(rs, "data_is_white"); d.isRed = readBoolean(rs, "data_is_red");
                d.isDev = readBoolean(rs, "data_is_dev");
                d.availableToPlayers = decodeStringList(rs.getString("data_available_to_players"));
                yield d;
            }
            case "FilterData" -> {
                PlacedObjectData.FilterData d = new PlacedObjectData.FilterData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.availableToPlayers = decodeStringList(rs.getString("data_available_to_players"));
                d.availableOnTimelines = decodeTimelines(rs.getString("data_available_on_timelines"));
                yield d;
            }
            case "RippleLevelFilterData" -> {
                PlacedObjectData.RippleLevelFilterData d = new PlacedObjectData.RippleLevelFilterData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.minimumRippleLevel = rs.getFloat("data_min_ripple_level");
                d.maximumRippleLevel = rs.getFloat("data_max_ripple_level");
                yield d;
            }
            case "PrinceFilterData" -> {
                PlacedObjectData.PrinceFilterData d = new PlacedObjectData.PrinceFilterData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.stage = rs.getInt("data_stage"); d.greaterOrEqual = readBoolean(rs, "data_greater_or_equal");
                d.postGame = readBoolean(rs, "data_post_game");
                yield d;
            }
            case "CellDistortionData" -> {
                PlacedObjectData.CellDistortionData d = new PlacedObjectData.CellDistortionData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.intensity = rs.getFloat("data_intensity"); d.scale = rs.getFloat("data_scale");
                d.chromaticIntensity = rs.getFloat("data_chromatic_intensity");
                d.timeMult = rs.getFloat("data_time_mult");
                yield d;
            }
            case "OEsphereData" -> {
                PlacedObjectData.OEsphereData d = new PlacedObjectData.OEsphereData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.depth = rs.getInt("data_depth"); d.lIntensity = rs.getFloat("data_l_intensity");
                yield d;
            }
            case "SnowSourceData" -> {
                PlacedObjectData.SnowSourceData d = new PlacedObjectData.SnowSourceData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.shape = rs.getString("data_shape"); d.intensity = rs.getFloat("data_intensity");
                d.noisiness = rs.getFloat("data_noisiness");
                yield d;
            }
            case "LocalBlizzardData" -> {
                PlacedObjectData.LocalBlizzardData d = new PlacedObjectData.LocalBlizzardData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.intensity = rs.getFloat("data_intensity"); d.scale = rs.getFloat("data_scale");
                d.angle = rs.getFloat("data_angle");
                yield d;
            }
            case "EnergySwirlData" -> {
                PlacedObjectData.EnergySwirlData d = new PlacedObjectData.EnergySwirlData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.colorType = rs.getString("data_color_type"); d.depth = rs.getFloat("data_depth");
                yield d;
            }
            case "SteamPipeData" -> {
                PlacedObjectData.SteamPipeData d = new PlacedObjectData.SteamPipeData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                yield d;
            }
            case "LightningMachineData" -> {
                PlacedObjectData.LightningMachineData d = new PlacedObjectData.LightningMachineData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.posX = rs.getDouble("data_pos_x"); d.posY = rs.getDouble("data_pos_y");
                d.startPointX = rs.getDouble("data_start_x"); d.startPointY = rs.getDouble("data_start_y");
                d.endPointX = rs.getDouble("data_end_x"); d.endPointY = rs.getDouble("data_end_y");
                d.chance = rs.getFloat("data_chance"); d.permanent = readBoolean(rs, "data_permanent");
                d.radial = readBoolean(rs, "data_radial"); d.width = rs.getFloat("data_width");
                d.intensity = rs.getFloat("data_intensity"); d.lifeTime = rs.getFloat("data_lifetime");
                d.lightningParam = rs.getFloat("data_lightning_param");
                d.lightningType = rs.getFloat("data_lightning_type");
                d.impact = rs.getInt("data_impact"); d.volume = rs.getFloat("data_volume");
                d.soundType = rs.getInt("data_sound_type"); d.random = readBoolean(rs, "data_random");
                d.light = readBoolean(rs, "data_light");
                yield d;
            }
            case "AdjustableFanData" -> {
                PlacedObjectData.AdjustableFanData d = new PlacedObjectData.AdjustableFanData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.spriteNum = rs.getInt("data_sprite_num"); d.speed = rs.getFloat("data_speed");
                d.scale = rs.getFloat("data_scale"); d.depth = rs.getFloat("data_depth");
                yield d;
            }
            case "HugeTurbineData" -> {
                PlacedObjectData.HugeTurbineData d = new PlacedObjectData.HugeTurbineData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.speed = rs.getFloat("data_speed"); d.depth = rs.getFloat("data_depth");
                yield d;
            }
            case "HarmfulSteamData" -> {
                PlacedObjectData.HarmfulSteamData d = new PlacedObjectData.HarmfulSteamData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.duration = rs.getFloat("data_duration"); d.frequency = rs.getFloat("data_frequency");
                d.lifetime = rs.getFloat("data_lifetime");
                yield d;
            }
            case "SkyWhalePathfindingData" -> {
                PlacedObjectData.SkyWhalePathfindingData d = new PlacedObjectData.SkyWhalePathfindingData();
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.index = rs.getInt("data_index");
                yield d;
            }
            case "WindData" -> {
                PlacedObjectData.WindData d = new PlacedObjectData.WindData();
                d.handle0X = rs.getDouble("data_handle_x"); d.handle0Y = rs.getDouble("data_handle_y");
                d.handle1X = rs.getDouble("data_handle2_x"); d.handle1Y = rs.getDouble("data_handle2_y");
                d.handle2X = rs.getDouble("data_handle3_x"); d.handle2Y = rs.getDouble("data_handle3_y");
                d.velocity = rs.getFloat("data_strength"); d.affectGroup = rs.getString("data_affect_group");
                yield d;
            }
            case "TerrainHandleData" -> {
                PlacedObjectData.TerrainHandleData d = new PlacedObjectData.TerrainHandleData();
                d.leftOffsetX = rs.getDouble("data_left_offset_x"); d.leftOffsetY = rs.getDouble("data_left_offset_y");
                d.rightOffsetX = rs.getDouble("data_right_offset_x"); d.rightOffsetY = rs.getDouble("data_right_offset_y");
                d.backHeight = rs.getFloat("data_back_height");
                yield d;
            }
            case "PomegranateData" -> {
                PlacedObjectData.PomegranateData d = new PlacedObjectData.PomegranateData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.minRegen = rs.getInt("data_min_regen"); d.maxRegen = rs.getInt("data_max_regen");
                d.handlePosX = rs.getDouble("data_handle_x"); d.handlePosY = rs.getDouble("data_handle_y");
                yield d;
            }
            case "WaterFlowData" -> {
                PlacedObjectData.WaterFlowData d = new PlacedObjectData.WaterFlowData();
                d.width = rs.getInt("data_sprite_num"); d.flow = rs.getFloat("data_flow");
                d.dynamic = readBoolean(rs, "data_dynamic"); d.preDelay = rs.getFloat("data_pre_delay");
                d.postDelay = rs.getFloat("data_post_delay");
                yield d;
            }
            case "DayNightData" -> {
                PlacedObjectData.DayNightData d = new PlacedObjectData.DayNightData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.duskPalette = rs.getInt("data_dusk_palette"); d.nightPalette = rs.getInt("data_night_palette");
                yield d;
            }
            case "FairyParticleData" -> {
                PlacedObjectData.FairyParticleData d = new PlacedObjectData.FairyParticleData();
                d.panelPosX = rs.getDouble("data_panel_x"); d.panelPosY = rs.getDouble("data_panel_y");
                d.absPulse = readBoolean(rs, "data_abs_pulse");
                d.pulseMin = rs.getFloat("data_pulse_min"); d.pulseMax = rs.getFloat("data_pulse_max");
                d.pulseRate = rs.getFloat("data_pulse_rate");
                d.scaleMin = rs.getFloat("data_scale_min"); d.scaleMax = rs.getFloat("data_scale_max");
                d.interpDurMin = rs.getFloat("data_interp_dur_min"); d.interpDurMax = rs.getFloat("data_interp_dur_max");
                d.interpDistMin = rs.getFloat("data_interp_dist_min"); d.interpDistMax = rs.getFloat("data_interp_dist_max");
                d.dirDevMin = rs.getFloat("data_dir_dev_min"); d.dirDevMax = rs.getFloat("data_dir_dev_max");
                d.dirMin = rs.getFloat("data_dir_min"); d.dirMax = rs.getFloat("data_dir_max");
                d.colorHmin = rs.getFloat("data_color_h_min"); d.colorHmax = rs.getFloat("data_color_h_max");
                d.colorSmin = rs.getFloat("data_color_s_min"); d.colorSmax = rs.getFloat("data_color_s_max");
                d.colorLmin = rs.getFloat("data_color_l_min"); d.colorLmax = rs.getFloat("data_color_l_max");
                d.interpTrans = rs.getFloat("data_interp_trans"); d.alphaTrans = rs.getFloat("data_alpha_trans");
                d.numKeyframes = rs.getFloat("data_num_keyframes");
                d.spriteType = rs.getString("data_sprite_type");
                d.dirLerpType = rs.getString("data_dir_lerp_type"); d.speedLerpType = rs.getString("data_speed_lerp_type");
                d.glowRad = rs.getFloat("data_glow_rad"); d.glowStrength = rs.getFloat("data_glow_strength");
                d.rotationRate = rs.getFloat("data_rotation_rate");
                yield d;
            }
            default -> null;
        };
    }

    private void deleteOwnedRows(String table, EffectOwner owner) throws SQLException {
        try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE owner_kind = ? AND region_name = ? AND room_name = ? AND template_name = ? AND timeline = ?")) {
            bindOwner(statement, owner);
            statement.executeUpdate();
        }
        catch(SQLException exception) {
            if(!isMissingTable(exception)) {
                throw exception;
            }
        }
    }

    private static void bindOwner(PreparedStatement statement, EffectOwner owner) throws SQLException {
        statement.setString(1, owner.kind);
        statement.setString(2, owner.regionName);
        statement.setString(3, owner.roomName);
        statement.setString(4, owner.templateName);
        statement.setString(5, owner.timeline);
    }

    private static boolean isMissingTable(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("no such table");
    }

    private static String timelineKey(Timeline timeline) {
        return timeline != null ? timeline.getValue() : "";
    }

    private static String storageTimelineKey(StoredRoomSettings settings) {
        if(!settings.mergedTimelines.isEmpty()) {
            return timelineGroupKey(settings.mergedTimelines);
        }
        return timelineKey(settings.timeline);
    }

    private static String regionStorageTimelineKey(Timeline timeline, List<Timeline> mergedTimelines) {
        if(timeline == null) {
            return "";
        }
        if(mergedTimelines != null && !mergedTimelines.isEmpty()) {
            return timelineGroupKey(mergedTimelines);
        }
        return timelineKey(timeline);
    }

    private static String timelineGroupKey(List<Timeline> timelines) {
        return timelines.stream()
                .map(timeline -> timeline != null ? timeline.getValue() : "")
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static Timeline timelineFromKey(String timeline) {
        if(timeline == null || timeline.isEmpty()) {
            return null;
        }
        if(timeline.contains(",")) {
            return null;
        }
        return Timeline.fromValue(timeline);
    }

    private static void setNullableDouble(PreparedStatement st, int idx, Double value) throws SQLException {
        if(value != null) st.setDouble(idx, value);
        else st.setNull(idx, java.sql.Types.REAL);
    }

    private static String encodeVertices(float[][] vertices) {
        if(vertices == null) return null;
        StringBuilder builder = new StringBuilder();
        for(float[] pair : vertices) {
            if(!builder.isEmpty()) builder.append('|');
            builder.append(pair[0]).append(',').append(pair[1]);
        }
        return builder.toString();
    }

    private static float[][] decodeVertices(String encoded) {
        if(encoded == null || encoded.isEmpty()) return new float[4][2];
        String[] pairs = encoded.split("\\|", -1);
        float[][] vertices = new float[pairs.length][2];
        for(int i = 0; i < pairs.length; i++) {
            String[] xy = pairs[i].split(",", 2);
            vertices[i][0] = xy.length > 0 ? Float.parseFloat(xy[0]) : 0f;
            vertices[i][1] = xy.length > 1 ? Float.parseFloat(xy[1]) : 0f;
        }
        return vertices;
    }

    private static String encodeFloatArray(float[] values) {
        if(values == null || values.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < values.length; i++) {
            if(i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static float[] decodeFloatArray(String encoded) {
        if(encoded == null || encoded.isEmpty()) {
            return null;
        }
        String[] parts = encoded.split(",");
        float[] values = new float[parts.length];
        for(int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i]);
        }
        return values;
    }

    private static String encodeStringArray(String[] values) {
        if(values == null || values.length == 0) {
            return null;
        }
        return String.join("\u001f", values);
    }

    private static String[] decodeStringArray(String encoded) {
        if(encoded == null || encoded.isEmpty()) {
            return null;
        }
        return encoded.split("\u001f", -1);
    }

    private static String encodeUnrecognized(Map<String, String> unrecognized) {
        if(unrecognized == null || unrecognized.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for(Map.Entry<String, String> entry : unrecognized.entrySet()) {
            if(!builder.isEmpty()) {
                builder.append('\u001e');
            }
            builder.append(entry.getKey()).append('\u001f').append(entry.getValue());
        }
        return builder.toString();
    }

    private static void decodeUnrecognized(String encoded, Map<String, String> unrecognized) {
        if(encoded == null || encoded.isEmpty()) {
            return;
        }
        for(String entry : encoded.split("\u001e", -1)) {
            String[] parts = entry.split("\u001f", 2);
            if(parts.length == 2) {
                unrecognized.put(parts[0], parts[1]);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private record EffectOwner(String kind, String regionName, String roomName, String templateName, String timeline) {
        private static EffectOwner room(String regionName, String roomName, String storageTimeline) {
            return new EffectOwner("room", regionName, roomName, "", storageTimeline);
        }

        private static EffectOwner template(String regionName, String templateName) {
            return new EffectOwner("template", regionName, "", templateName, "");
        }
    }

    private record StoredRegionTimelineVariant(StoredRegion region, Timeline timeline, List<Timeline> mergedTimelines) {}

    private static final class RegionPropertiesBinder {

        private RegionPropertiesBinder() {}

        static int bind(PreparedStatement statement, int startIndex, StoredRegionProperties properties, String displayName) throws SQLException {
            int index = startIndex;
            String resolvedDisplayName = displayName != null ? displayName : properties.displayName;
            setNullableString(statement, index++, resolvedDisplayName);
            setNullableInt(statement, index++, properties.batDepleteCyclesMin);
            setNullableInt(statement, index++, properties.batDepleteCyclesMax);
            setNullableInt(statement, index++, properties.batDepleteCyclesMaxIfLessThanTwoLeft);
            setNullableInt(statement, index++, properties.batDepleteCyclesMaxIfLessThanFiveLeft);
            setNullableFloat(statement, index++, properties.overseersSpawnChance);
            setNullableFloat(statement, index++, properties.playerGuideOverseerSpawnChance);
            setNullableInt(statement, index++, properties.overseersMin);
            setNullableInt(statement, index++, properties.overseersMax);
            setNullableInt(statement, index++, properties.batsPerActiveSwarmRoom);
            setNullableInt(statement, index++, properties.batsPerInactiveSwarmRoom);
            setNullableInt(statement, index++, properties.scavsMin);
            setNullableInt(statement, index++, properties.scavsMax);
            setNullableFloat(statement, index++, properties.scavsSpawnChance);
            setNullableFloat(statement, index++, properties.cloudsStart);
            setNullableFloat(statement, index++, properties.cloudsEnd);
            setNullableString(statement, index++, properties.corruptionEffectColor);
            setNullableString(statement, index++, properties.corruptionEyeColor);
            setNullableString(statement, index++, properties.kelpColor);
            setNullableBoolean(statement, index++, properties.albinos);
            setNullableFloat(statement, index++, properties.blackSalamanderChance);
            setNullableString(statement, index++, properties.proceduralMusicBank);
            setNullableInt(statement, index++, properties.scavengerDelayInitialMin);
            setNullableInt(statement, index++, properties.scavengerDelayInitialMax);
            setNullableInt(statement, index++, properties.scavengerDelayRepeatMin);
            setNullableInt(statement, index++, properties.scavengerDelayRepeatMax);
            setNullableFloat(statement, index++, properties.slugPupSpawnChance);
            setNullableBoolean(statement, index++, properties.glacialWasteland);
            setNullableFloat(statement, index++, properties.earlyCycleChance);
            setNullableFloat(statement, index++, properties.earlyCycleFloodChance);
            setNullableInt(statement, index++, properties.rotPalette);
            setNullableString(statement, index++, properties.mudColor);
            setNullableFloat(statement, index++, properties.fuseSize);
            setNullableString(statement, index++, properties.mapSkyLayers);
            setNullableBoolean(statement, index++, properties.hideTimer);
            setNullableString(statement, index++, properties.waterColorOverride);
            setNullableString(statement, index++, properties.globalCreatureFlags);
            setNullableString(statement, index++, encodeStringList(properties.subregions));
            setNullableString(statement, index++, properties.boxWormColor);
            setNullableInt(statement, index++, properties.rippleSpiderThreshold);
            setNullableString(statement, index++, encodeUnrecognized(properties.unrecognized));
            setNullableInt(statement, index++, properties.palette);
            setNullableInt(statement, index++, properties.fadePalette);
            return index;
        }

        static void read(ResultSet resultSet, StoredRegion region) throws SQLException {
            region.displayName = resultSet.getString("display_name");
            region.batDepleteCyclesMin = readNullableInt(resultSet, "bat_deplete_cycles_min");
            region.batDepleteCyclesMax = readNullableInt(resultSet, "bat_deplete_cycles_max");
            region.batDepleteCyclesMaxIfLessThanTwoLeft = readNullableInt(resultSet, "bat_deplete_cycles_max_if_less_than_two_left");
            region.batDepleteCyclesMaxIfLessThanFiveLeft = readNullableInt(resultSet, "bat_deplete_cycles_max_if_less_than_five_left");
            region.overseersSpawnChance = readNullableFloat(resultSet, "overseers_spawn_chance");
            region.playerGuideOverseerSpawnChance = readNullableFloat(resultSet, "player_guide_overseer_spawn_chance");
            region.overseersMin = readNullableInt(resultSet, "overseers_min");
            region.overseersMax = readNullableInt(resultSet, "overseers_max");
            region.batsPerActiveSwarmRoom = readNullableInt(resultSet, "bats_per_active_swarm_room");
            region.batsPerInactiveSwarmRoom = readNullableInt(resultSet, "bats_per_inactive_swarm_room");
            region.scavsMin = readNullableInt(resultSet, "scavs_min");
            region.scavsMax = readNullableInt(resultSet, "scavs_max");
            region.scavsSpawnChance = readNullableFloat(resultSet, "scavs_spawn_chance");
            region.cloudsStart = readNullableFloat(resultSet, "clouds_start");
            region.cloudsEnd = readNullableFloat(resultSet, "clouds_end");
            region.corruptionEffectColor = resultSet.getString("corruption_effect_color");
            region.corruptionEyeColor = resultSet.getString("corruption_eye_color");
            region.kelpColor = resultSet.getString("kelp_color");
            region.albinos = readNullableBoolean(resultSet, "albinos");
            region.blackSalamanderChance = readNullableFloat(resultSet, "black_salamander_chance");
            region.proceduralMusicBank = resultSet.getString("procedural_music_bank");
            region.scavengerDelayInitialMin = readNullableInt(resultSet, "scavenger_delay_initial_min");
            region.scavengerDelayInitialMax = readNullableInt(resultSet, "scavenger_delay_initial_max");
            region.scavengerDelayRepeatMin = readNullableInt(resultSet, "scavenger_delay_repeat_min");
            region.scavengerDelayRepeatMax = readNullableInt(resultSet, "scavenger_delay_repeat_max");
            region.slugPupSpawnChance = readNullableFloat(resultSet, "slug_pup_spawn_chance");
            region.glacialWasteland = readNullableBoolean(resultSet, "glacial_wasteland");
            region.earlyCycleChance = readNullableFloat(resultSet, "early_cycle_chance");
            region.earlyCycleFloodChance = readNullableFloat(resultSet, "early_cycle_flood_chance");
            region.rotPalette = readNullableInt(resultSet, "rot_palette");
            region.mudColor = resultSet.getString("mud_color");
            region.fuseSize = readNullableFloat(resultSet, "fuse_size");
            region.mapSkyLayers = resultSet.getString("map_sky_layers");
            region.hideTimer = readNullableBoolean(resultSet, "hide_timer");
            region.waterColorOverride = resultSet.getString("water_color_override");
            region.globalCreatureFlags = resultSet.getString("global_creature_flags");
            region.subregions.addAll(decodeStringList(resultSet.getString("subregions")));
            region.boxWormColor = resultSet.getString("box_worm_color");
            region.rippleSpiderThreshold = readNullableInt(resultSet, "ripple_spider_threshold");
            decodeUnrecognized(resultSet.getString("unrecognized_properties"), region.unrecognizedProperties);
            region.palette = readNullableInt(resultSet, "palette");
            region.fadePalette = readNullableInt(resultSet, "fade_palette");
        }

        private static Boolean readNullableBoolean(ResultSet resultSet, String column) throws SQLException {
            int value = resultSet.getInt(column);
            return resultSet.wasNull() ? null : value != 0;
        }
    }
}
