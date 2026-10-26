package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private var appliedMapType: Int? = null

    override fun hasMarker(): Boolean {
        // TODO: if (!mMarker?.isVisible!!){
        if (mMarker != null) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        // TODO: mMarker?.isVisible = true
        if (mMarker == null) {
            mMarker = mMap.addMarker(
                MarkerOptions().position(it)
            )
        } else {
            mMarker?.position = it!!
        }
    }
    private fun removeMarker() {
        mMarker?.remove() // mMarker?.isVisible = false
        mMarker = null
    }
    override fun initializeMap() {
        val key = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData.getString("com.maplibre.AccessToken")
        MapLibre.getInstance(this, key, WellKnownTileServer.Mapbox)
        // val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun refreshMapType() {
        if (!::mMap.isInitialized) return

        val selectedMapType = viewModel.mapType
        if (selectedMapType == appliedMapType) return

        appliedMapType = selectedMapType
        val typeUrl = when (selectedMapType) {
            2 -> "mapbox://styles/mapbox/satellite-streets-v12"
            3 -> "mapbox://styles/mapbox/outdoors-v12"
            4 -> "mapbox://styles/mapbox/navigation-day-v1"
            else -> "mapbox://styles/mapbox/streets-v12"
        }
        mMap.setStyle(typeUrl) { style ->
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationComponent = mMap.locationComponent
                locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(this, style)
                        .useDefaultLocationEngine(true)
                        .build()
                )
                locationComponent.isLocationComponentEnabled = true
                locationComponent.cameraMode = CameraMode.TRACKING
                locationComponent.renderMode = RenderMode.COMPASS
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99)
            }
        }
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                // mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f.toDouble()))
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    // TODO:
                    // isVisible = true
                    // showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // maplibre custom ui
            refreshMapType()
            // TODO: fix bug with drawer
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoMargins(0,0,0,80)
            uiSettings.setAttributionEnabled(false)
            // uiSettings.setAttributionMargins(80,0,0,80)
            // setPadding(0,0,0,80)


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                updateMarker(it!!)
                // TODO: MarkerOptions().position(it!!)
                // .draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED).visible(false)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
    }
    override fun onMapClick(latLng: LatLng): Boolean {
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                // marker.isVisible = true
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
                clearSelectedFavorite()
            }
        }
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            val favoriteName = selectedFavoriteName
            showStartNotification(
                favoriteName ?: getString(R.string.location_coordinates, lat, lon)
            )
            if (favoriteName == null) {
                lifecycleScope.launch {
                    mLatLng?.getAddress(getActivityInstance())?.let { address ->
                        address.collect { value ->
                            showStartNotification(
                                value.ifBlank { getString(R.string.location_coordinates, lat, lon) }
                            )
                        }
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
