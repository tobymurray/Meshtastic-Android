package com.geeksville.mesh.ui.map

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geeksville.mesh.BuildConfig
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MeshProtos.Waypoint
import com.geeksville.mesh.NodeInfo
import com.geeksville.mesh.R
import com.geeksville.mesh.android.BuildUtils.debug
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.android.getLocationPermissions
import com.geeksville.mesh.android.gpsDisabled
import com.geeksville.mesh.android.hasGps
import com.geeksville.mesh.android.hasLocationPermission
import com.geeksville.mesh.copy
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.model.map.CustomOverlayManager
import com.geeksville.mesh.model.map.CustomTileSource
import com.geeksville.mesh.model.map.MarkerWithLabel
import com.geeksville.mesh.ui.ScreenFragment
import com.geeksville.mesh.ui.map.components.CacheLayout
import com.geeksville.mesh.ui.map.components.DownloadButton
import com.geeksville.mesh.ui.map.components.EditWaypointDialog
import com.geeksville.mesh.ui.components.IconButton
import com.geeksville.mesh.ui.map.components.rememberMapViewWithLifecycle
import com.geeksville.mesh.util.SqlTileWriterExt
import com.geeksville.mesh.util.requiredZoomLevel
import com.geeksville.mesh.util.formatAgo
import com.geeksville.mesh.util.zoomIn
import com.geeksville.mesh.waypoint
import com.google.accompanist.themeadapter.appcompat.AppCompatTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicyException
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.DefaultOverlayManager
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gridlines.LatLonGridlineOverlay2
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.DateFormat


@AndroidEntryPoint
class MapFragment : ScreenFragment("Map Fragment"), Logging {

    private val model: UIViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppCompatTheme {
                    MapView(model)
                }
            }
        }
    }
}

@Composable
fun MapView(model: UIViewModel = viewModel()) {

    // UI Elements
    var cacheEstimate by remember { mutableStateOf("") }

    // constants
    val defaultMinZoom = 1.5
    val defaultMaxZoom = 18.0
    val prefsName = "org.geeksville.osm.prefs"
    val mapStyleId = "map_style_id"

    // Distance of bottom corner to top corner of bounding box
    val zoomLevelLowest = 13.0 // approx 5 miles long
    val zoomLevelMiddle = 12.25 // approx 10 miles long
    val zoomLevelHighest = 11.5 // approx 15 miles long

    var zoomLevelMin = 0.0
    var zoomLevelMax = 0.0

    // Map Elements
    var downloadRegionBoundingBox: BoundingBox? by remember { mutableStateOf(null) }
    var myLocationOverlay: MyLocationNewOverlay? by remember { mutableStateOf(null) }

    val context = LocalContext.current
    val mPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val haptic = LocalHapticFeedback.current
    fun performHapticFeedback() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    val hasGps = context.hasGps()

    val map = rememberMapViewWithLifecycle(context)

    fun MapView.toggleMyLocation() {
        if (context.gpsDisabled()) {
            debug("Telling user we need location turned on for MyLocationNewOverlay")
            model.showSnackbar(R.string.location_disabled)
            return
        }
        debug("user clicked MyLocationNewOverlay ${myLocationOverlay == null}")
        if (myLocationOverlay == null) {
            myLocationOverlay = MyLocationNewOverlay(this).apply {
                enableMyLocation()
                enableFollowLocation()
                AppCompatResources.getDrawable(context, R.drawable.ic_location_dot_24)?.let {
                    setPersonIcon(it.toBitmap())
                    setPersonAnchor(0.5f, 0.5f)
                }
            }
            overlays.add(myLocationOverlay)
        } else {
            myLocationOverlay?.apply {
                disableMyLocation()
                disableFollowLocation()
            }
            overlays.remove(myLocationOverlay)
            myLocationOverlay = null
        }
    }

    val requestPermissionAndToggleLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) map.toggleMyLocation()
        }

    fun requestPermissionAndToggle() {
        requestPermissionAndToggleLauncher.launch(context.getLocationPermissions())
    }

    val nodes by model.nodeDB.nodes.observeAsState(emptyMap())
    val waypoints by model.waypoints.observeAsState(emptyMap())

    var showDownloadButton: Boolean by remember { mutableStateOf(false) }
    var showEditWaypointDialog by remember { mutableStateOf<Waypoint?>(null) }
    var showCurrentCacheInfo by remember { mutableStateOf(false) }

    fun MapView.onNodesChanged(nodes: Collection<NodeInfo>): List<MarkerWithLabel> {
        val nodesWithPosition = nodes.filter { it.validPosition != null }
        val ic = ContextCompat.getDrawable(context, R.drawable.ic_baseline_location_on_24)
        val ourNode = model.ourNodeInfo.value
        return nodesWithPosition.map { node ->
            val (p, u) = node.position!! to node.user!!
            MarkerWithLabel(this, "${u.longName} ${formatAgo(p.time)}").apply {
                id = u.id
                title = "${u.longName} ${node.batteryStr}"
                snippet = model.gpsString(p)
                ourNode?.distanceStr(node, model.config.display.units.number)?.let { dist ->
                    subDescription =
                        context.getString(R.string.map_subDescription, ourNode.bearing(node), dist)
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                position = GeoPoint(p.latitude, p.longitude)
                icon = ic
            }
        }
    }

    fun showDeleteMarkerDialog(waypoint: Waypoint) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.waypoint_delete)
        builder.setNeutralButton(R.string.cancel) { _, _ ->
            debug("User canceled marker delete dialog")
        }
        builder.setNegativeButton(R.string.delete_for_me) { _, _ ->
            debug("User deleted waypoint ${waypoint.id} for me")
            model.deleteWaypoint(waypoint.id)
        }
        if (waypoint.lockedTo in setOf(0, model.myNodeNum ?: 0) && model.isConnected())
            builder.setPositiveButton(R.string.delete_for_everyone) { _, _ ->
                debug("User deleted waypoint ${waypoint.id} for everyone")
                model.sendWaypoint(waypoint.copy { expire = 1 })
                model.deleteWaypoint(waypoint.id)
            }
        val dialog = builder.show()
        for (button in setOf(
            androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL,
            androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,
            androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
        )) with(dialog.getButton(button)) { textSize = 12F; isAllCaps = false }
    }

    fun showMarkerLongPressDialog(id: Int) {
        performHapticFeedback()
        debug("marker long pressed id=${id}")
        val waypoint = model.waypoints.value?.get(id)?.data?.waypoint ?: return
        // edit only when unlocked or lockedTo myNodeNum
        if (waypoint.lockedTo in setOf(0, model.myNodeNum ?: 0) && model.isConnected())
            showEditWaypointDialog = waypoint
        else
            showDeleteMarkerDialog(waypoint)
    }

    fun getUsername(id: String?) = if (id == DataPacket.ID_LOCAL) context.getString(R.string.you)
    else model.nodeDB.nodes.value?.get(id)?.user?.longName
        ?: context.getString(R.string.unknown_username)

    fun MapView.onWaypointChanged(waypoints: Collection<Packet>): List<MarkerWithLabel> {
        return waypoints.mapNotNull { waypoint ->
            val pt = waypoint.data.waypoint ?: return@mapNotNull null
            val lock = if (pt.lockedTo != 0) "\uD83D\uDD12" else ""
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(waypoint.received_time)
            val label = pt.name + " " + formatAgo((waypoint.received_time / 1000).toInt())
            val emoji = String(Character.toChars(if (pt.icon == 0) 128205 else pt.icon))
            MarkerWithLabel(this, label, emoji).apply {
                id = "${pt.id}"
                title = "${pt.name} (${getUsername(waypoint.data.from)}$lock)"
                snippet = "[$time] " + pt.description
                position = GeoPoint(pt.latitudeI * 1e-7, pt.longitudeI * 1e-7)
                setVisible(false)
                setOnLongClickListener {
                    showMarkerLongPressDialog(pt.id)
                    true
                }
            }
        }
    }

    fun purgeTileSource() {
        val cache = SqlTileWriterExt()
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.map_tile_source)
        val sources = cache.sources
        val sourceList = mutableListOf<String>()
        for (i in sources.indices) {
            sourceList.add(sources[i].source as String)
        }
        val selected: BooleanArray? = null
        val selectedList = mutableListOf<Int>()
        builder.setMultiChoiceItems(
            sourceList.toTypedArray(),
            selected
        ) { _, i, b ->
            if (b) {
                selectedList.add(i)
            } else {
                selectedList.remove(i)
            }

        }
        builder.setPositiveButton(R.string.clear) { _, _ ->
            for (x in selectedList) {
                val item = sources[x]
                val b = cache.purgeCache(item.source)
                if (b) model.showSnackbar(
                    context.getString(R.string.map_purge_success, item.source)
                ) else model.showSnackbar(
                    context.getString(R.string.map_purge_fail)
                )
            }
        }
        builder.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    LaunchedEffect(showCurrentCacheInfo) {
        if (!showCurrentCacheInfo) return@LaunchedEffect
        model.showSnackbar(R.string.calculating)
        val cacheManager = CacheManager(map) // Make sure CacheManager has latest from map
        val cacheCapacity = cacheManager.cacheCapacity()
        val currentCacheUsage = cacheManager.currentCacheUsage()

        val mapCacheInfoText = context.getString(
            R.string.map_cache_info,
            cacheCapacity / (1024.0 * 1024.0),
            currentCacheUsage / (1024.0 * 1024.0)
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.map_cache_manager)
            .setMessage(mapCacheInfoText)
            .setPositiveButton(R.string.close) { dialog, _ ->
                showCurrentCacheInfo = false
                dialog.dismiss()
            }
            .show()
    }

    fun downloadRegion(
        cacheManager: CacheManager,
        writer: SqliteArchiveTileWriter,
        bb: BoundingBox,
        zoomMin: Int,
        zoomMax: Int
    ) {
        cacheManager.downloadAreaAsync(
            context,
            bb,
            zoomMin,
            zoomMax,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    model.showSnackbar(R.string.map_download_complete)
                    writer.onDetach()
                }

                override fun onTaskFailed(errors: Int) {
                    model.showSnackbar(context.getString(R.string.map_download_errors, errors))
                    writer.onDetach()
                }

                override fun updateProgress(
                    progress: Int,
                    currentZoomLevel: Int,
                    zoomMin: Int,
                    zoomMax: Int
                ) {
                    //NOOP since we are using the build in UI
                }

                override fun downloadStarted() {
                    //NOOP since we are using the build in UI
                }

                override fun setPossibleTilesInArea(total: Int) {
                    //NOOP since we are using the build in UI
                }
            })
    }

    /**
     * Create LatLong Grid line overlay
     * @param enabled: turn on/off gridlines
     */
    fun MapView.createLatLongGrid(enabled: Boolean) {
        val latLongGridOverlay = LatLonGridlineOverlay2()
        latLongGridOverlay.isEnabled = enabled
        if (latLongGridOverlay.isEnabled) {
            val textPaint = Paint()
            textPaint.textSize = 40f
            textPaint.color = Color.GRAY
            textPaint.isAntiAlias = true
            textPaint.isFakeBoldText = true
            textPaint.textAlign = Paint.Align.CENTER
            latLongGridOverlay.textPaint = textPaint
            latLongGridOverlay.setBackgroundColor(Color.TRANSPARENT)
            latLongGridOverlay.setLineWidth(3.0f)
            latLongGridOverlay.setLineColor(Color.GRAY)
            overlays.add(latLongGridOverlay)
        }
    }

    /**
     * Adds copyright to map depending on what source is showing
     */
    fun MapView.addCopyright() {
        if (overlays.none { it is CopyrightOverlay }) {
            val copyrightNotice: String = tileProvider.tileSource.copyrightNotice ?: return
            val copyrightOverlay = CopyrightOverlay(context)
            copyrightOverlay.setCopyrightNotice(copyrightNotice)
            overlays.add(copyrightOverlay)
        }
    }

    val mapEventsReceiver = object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
            InfoWindow.closeAllInfoWindowsOn(map)
            return true
        }

        override fun longPressHelper(p: GeoPoint): Boolean {
            performHapticFeedback()
            val enabled = model.isConnected() && downloadRegionBoundingBox == null

            if (enabled) showEditWaypointDialog = waypoint {
                latitudeI = (p.latitude * 1e7).toInt()
                longitudeI = (p.longitude * 1e7).toInt()
            }
            return true
        }
    }

    fun MapView.drawOverlays() {
        if (overlays.none { it is MapEventsOverlay }) {
            overlays.add(0, MapEventsOverlay(mapEventsReceiver))
        }
        if (myLocationOverlay != null && overlays.none { it is MyLocationNewOverlay }) {
            overlays.add(myLocationOverlay)
        }
        addCopyright()  // Copyright is required for certain map sources
        createLatLongGrid(false)

        overlays.removeAll(overlays.filterIsInstance<MarkerWithLabel>())
        val nodeMarkers = onNodesChanged(nodes.values)
        val waypointMarkers = onWaypointChanged(waypoints.values)
        debug("Showing on map: ${nodeMarkers.size} nodes ${waypointMarkers.size} waypoints")

        overlays.addAll(nodeMarkers + waypointMarkers)
        invalidate()
    }

    // FIXME workaround to 'nodes.observeAsState' going stale after MapFragment enters onPause state
    LaunchedEffect(Unit) {
        while (true) {
            if (downloadRegionBoundingBox == null) map.drawOverlays()
            delay(30000L)
        }
    }

//    private fun addWeatherLayer() {
//        if (map.tileProvider.tileSource.name()
//                .equals(CustomTileSource.getTileSource("ESRI World TOPO").name())
//        ) {
//            val layer = TilesOverlay(
//                MapTileProviderBasic(
//                    activity,
//                    CustomTileSource.OPENWEATHER_RADAR
//                ), context
//            )
//            layer.loadingBackgroundColor = Color.TRANSPARENT
//            layer.loadingLineColor = Color.TRANSPARENT
//            map.overlayManager.add(layer)
//        }
//    }

    fun MapView.zoomToNodes() {
        val nodeMarkers = onNodesChanged(nodes.values)
        if (nodeMarkers.isNotEmpty()) {
            val box = BoundingBox.fromGeoPoints(nodeMarkers.map { it.position })
            val center = GeoPoint(box.centerLatitude, box.centerLongitude)
            val maximumZoomLevel = tileProvider.tileSource.maximumZoomLevel.toDouble()
            val finalZoomLevel = minOf(box.requiredZoomLevel() * 0.8, maximumZoomLevel)
            controller.setCenter(center)
            controller.setZoom(finalZoomLevel)
        } else controller.zoomIn()
    }

    fun loadOnlineTileSourceBase(): ITileSource {
        val id = mPrefs.getInt(mapStyleId, 1)
        debug("mapStyleId from prefs: $id")
        return CustomTileSource.getTileSource(id).also {
            showDownloadButton =
                if (it is OnlineTileSourceBase) it.tileSourcePolicy.acceptsBulkDownload() else false
        }
    }

    /**
     * Creates Box overlay showing what area can be downloaded
     */
    fun MapView.generateBoxOverlay(zoomLevel: Double) {
        if (overlayManager !is CustomOverlayManager) {
            overlayManager = CustomOverlayManager(TilesOverlay(tileProvider, context))
            setMultiTouchControls(false)
            zoomLevelMax = tileProvider.tileSource.maximumZoomLevel.toDouble()
            drawOverlays()
        } else {
            overlays.removeAll(overlays.filterIsInstance<Polygon>())
        }
        val zoomFactor = 1.3 // zoom difference between view and download area polygon
        controller.setZoom(zoomLevel - zoomFactor)
        zoomLevelMin = zoomLevel
        downloadRegionBoundingBox = boundingBox.zoomIn(zoomFactor)
        val polygon = Polygon().apply {
            points = Polygon.pointsAsRect(downloadRegionBoundingBox).map {
                GeoPoint(it.latitude, it.longitude)
            }
        }
        overlays.add(polygon)
        val tileCount: Int = CacheManager(this).possibleTilesInArea(
            downloadRegionBoundingBox,
            zoomLevelMin.toInt(),
            zoomLevelMax.toInt()
        )
        cacheEstimate = context.getString(R.string.map_cache_tiles, tileCount)
    }

    fun startDownload() {
        val boundingBox = downloadRegionBoundingBox ?: return
        try {
            val outputName = buildString {
                append(Configuration.getInstance().osmdroidBasePath.absolutePath)
                append(File.separator)
                append("mainFile.sqlite") // TODO: Accept filename input param from user
            }
            val writer = SqliteArchiveTileWriter(outputName)
            val cacheManager = CacheManager(map, writer) // Make sure cacheManager has latest from map
            //this triggers the download
            downloadRegion(
                cacheManager,
                writer,
                boundingBox,
                zoomLevelMin.toInt(),
                zoomLevelMax.toInt(),
            )
        } catch (ex: TileSourcePolicyException) {
            debug("Tile source does not allow archiving: ${ex.message}")
        } catch (ex: Exception) {
            debug("Tile source exception: ${ex.message}")
        }
    }

    fun showMapStyleDialog() {
        val builder = MaterialAlertDialogBuilder(context)
        val mapStyles: Array<CharSequence> = CustomTileSource.mTileSources.values.toTypedArray()

        val mapStyleInt = mPrefs.getInt(mapStyleId, 1)
        builder.setSingleChoiceItems(mapStyles, mapStyleInt) { dialog, which ->
            debug("Set mapStyleId pref to $which")
            mPrefs.edit().putInt(mapStyleId, which).apply()
            dialog.dismiss()
            map.setTileSource(loadOnlineTileSourceBase())
        }
        val dialog = builder.create()
        dialog.show()
    }

    fun showCacheManagerDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.map_offline_manager)
            .setItems(
                arrayOf<CharSequence>(
                    context.getString(R.string.map_cache_size),
                    context.getString(R.string.map_download_region),
                    context.getString(R.string.map_clear_tiles),
                    context.getString(R.string.cancel)
                )
            ) { dialog, which ->
                when (which) {
                    0 -> showCurrentCacheInfo = true
                    1 -> {
                        map.generateBoxOverlay(zoomLevelHighest)
                        dialog.dismiss()
                    }

                    2 -> purgeTileSource()
                    else -> dialog.dismiss()
                }
            }.show()
    }

    Scaffold(
        floatingActionButton = {
            DownloadButton(showDownloadButton && downloadRegionBoundingBox == null) { showCacheManagerDialog() }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AndroidView(
                factory = {
                    map.apply {
                        // Required to get online tiles
                        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
                        setTileSource(loadOnlineTileSourceBase())
                        setDestroyMode(false) // keeps map instance alive when in the background
                        isVerticalMapRepetitionEnabled = false // disables map repetition
                        overlayManager = DefaultOverlayManager(TilesOverlay(tileProvider, context))
                        setMultiTouchControls(true)
                        setScrollableAreaLimitLatitude( // bounds scrollable map
                            overlayManager.tilesOverlay.bounds.actualNorth,
                            overlayManager.tilesOverlay.bounds.actualSouth,
                            0
                        )
                        // scales the map tiles to the display density of the screen
                        isTilesScaledToDpi = true
                        // sets the minimum zoom level (the furthest out you can zoom)
                        minZoomLevel = defaultMinZoom
                        maxZoomLevel = defaultMaxZoom
                        // Disables default +/- button for zooming
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        zoomToNodes()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    if (downloadRegionBoundingBox == null) map.drawOverlays()
                    else map.generateBoxOverlay(zoomLevelMin)
                },
            )
            if (downloadRegionBoundingBox != null) CacheLayout(
                cacheEstimate = cacheEstimate,
                onExecuteJob = { startDownload() },
                onCancelDownload = {
                    downloadRegionBoundingBox = null
                    map.apply {
                        overlayManager = DefaultOverlayManager(TilesOverlay(tileProvider, context))
                        setMultiTouchControls(true)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) else Column(
                modifier = Modifier
                    .padding(top = 16.dp, end = 16.dp)
                    .align(Alignment.TopEnd),
            ) {
                IconButton(
                    onClick = { showMapStyleDialog() },
                    drawableRes = R.drawable.ic_twotone_layers_24,
                    contentDescription = R.string.map_style_selection,
                )
                IconButton(
                    onClick = {
                        if (context.hasLocationPermission()) map.toggleMyLocation()
                        else requestPermissionAndToggle()
                    },
                    enabled = hasGps,
                    drawableRes = if (myLocationOverlay == null) R.drawable.ic_twotone_my_location_24
                    else R.drawable.ic_twotone_location_disabled_24,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (showEditWaypointDialog != null) {
        EditWaypointDialog(
            waypoint = showEditWaypointDialog ?: return,
            onSendClicked = { waypoint ->
                debug("User clicked send waypoint ${waypoint.id}")
                showEditWaypointDialog = null
                model.sendWaypoint(waypoint.copy {
                    if (id == 0) id = model.generatePacketId() ?: return@EditWaypointDialog
                    expire = Int.MAX_VALUE // TODO add expire picker
                    lockedTo = if (waypoint.lockedTo != 0) model.myNodeNum ?: 0 else 0
                })

            },
            onDeleteClicked = { waypoint ->
                debug("User clicked delete waypoint ${waypoint.id}")
                showEditWaypointDialog = null
                showDeleteMarkerDialog(waypoint)
            },
            onDismissRequest = {
                debug("User clicked cancel marker edit dialog")
                showEditWaypointDialog = null
            },
        )
    }
}
