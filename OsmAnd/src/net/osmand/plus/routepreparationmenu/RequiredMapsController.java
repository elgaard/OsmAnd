package net.osmand.plus.routepreparationmenu;

import android.content.Intent;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.osmand.Location;
import net.osmand.OnResultCallback;
import net.osmand.data.LatLon;
import net.osmand.gpx.GPXUtilities.WptPt;
import net.osmand.map.WorldRegion;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.dialog.DialogManager;
import net.osmand.plus.base.dialog.interfaces.controller.IDialogController;
import net.osmand.plus.download.DownloadIndexesThread;
import net.osmand.plus.download.DownloadIndexesThread.DownloadEvents;
import net.osmand.plus.download.DownloadItem;
import net.osmand.plus.download.DownloadResources;
import net.osmand.plus.download.DownloadValidationManager;
import net.osmand.plus.download.IndexItem;
import net.osmand.plus.myplaces.tracks.ItemsSelectionHelper;
import net.osmand.plus.onlinerouting.OnlineRoutingHelper;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteProvider;
import net.osmand.router.MissingMapsCalculator;
import net.osmand.router.RoutePlannerFrontEnd;
import net.osmand.util.Algorithms;
import net.osmand.util.CollectionUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RequiredMapsController implements IDialogController, DownloadEvents {

	public static final String PROCESS_ID = "download_missing_maps";

	private final OsmandApplication app;

	private List<DownloadItem> missingMaps = new ArrayList<>();
	private List<DownloadItem> mapsToUpdate = new ArrayList<>();
	private List<DownloadItem> usedMaps = new ArrayList<>();
	private ItemsSelectionHelper<DownloadItem> itemsSelectionHelper = new ItemsSelectionHelper<>();

	private boolean loadingMapsInProgress = false;
	private boolean onlineCalculationRequested = false;

	public RequiredMapsController(@NonNull OsmandApplication app) {
		this.app = app;
		initContent();
	}

	public void initContent() {
		DownloadIndexesThread downloadThread = app.getDownloadThread();
		boolean internetConnectionAvailable = app.getSettings().isInternetConnectionAvailable();
		if (!downloadThread.getIndexes().isDownloadedFromInternet) {
			if (internetConnectionAvailable) {
				downloadThread.runReloadIndexFiles();
				loadingMapsInProgress = true;
			}
		}
		updateSelectionHelper();
	}

	private void updateSelectionHelper() {
		if (!loadingMapsInProgress) {
			updateMapsToDownload();
			itemsSelectionHelper.setAllItems(CollectionUtils.asOneList(missingMaps, mapsToUpdate));
			itemsSelectionHelper.setSelectedItems(missingMaps);
		}
	}

	@NonNull
	public List<DownloadItem> getMissingMaps() {
		return missingMaps;
	}

	@NonNull
	public List<DownloadItem> getOutdatedMaps() {
		return mapsToUpdate;
	}

	@NonNull
	public List<DownloadItem> getUsedMaps() {
		return usedMaps;
	}

	public boolean isItemSelected(@NonNull DownloadItem downloadItem) {
		return itemsSelectionHelper.isItemSelected(downloadItem);
	}

	private void updateMapsToDownload() {
		RouteCalculationResult result = app.getRoutingHelper().getRoute();
		this.missingMaps = collectMapsForRegions(result.getMissingMaps());
		this.mapsToUpdate = collectMapsForRegions(result.getMapsToUpdate());
		this.usedMaps = collectMapsForRegions(result.getUsedMaps());
	}

	private List<DownloadItem> collectMapsForRegions(@NonNull List<WorldRegion> regions) {
		List<DownloadItem> result = new ArrayList<>();
		DownloadResources resources = app.getDownloadThread().getIndexes();
		if (!Algorithms.isEmpty(regions)) {
			for (WorldRegion missingRegion : regions) {
				for (DownloadItem downloadItem : resources.getDownloadItems(missingRegion)) {
					if (Objects.equals(downloadItem.getType().getTag(), "map")) {
						result.add(downloadItem);
					}
				}
			}
		}
		return result;
	}

	public void onCalculateOnlineButtonClicked() {
		onlineCalculationRequested = true;
		loadingMapsInProgress = true;
		askRefreshDialog();

		runAsync(() -> {
			MissingMapsCalculator missingMapsCalculator = RoutePlannerFrontEnd.getMissingMapsCalculator();
			if (missingMapsCalculator != null) {
				LatLon startPoint = missingMapsCalculator.getStartPoint();
				LatLon endPoint = missingMapsCalculator.getEndPoint();
				onlineCalculateRequestStartPoint(startPoint, endPoint, locations -> {
					List<LatLon> latLonList = new ArrayList<>();
					for (Location location : locations) {
						latLonList.add(new LatLon(location.getLatitude(), location.getLongitude()));
					}
					try {
						missingMapsCalculator.checkIfThereAreMissingMaps(latLonList);
						loadingMapsInProgress = false;
						updateSelectionHelper();
						askRefreshDialog();
					} catch (IOException e) {
						onErrorReceived(e.getMessage());
					}
				});
			}
		});
	}

	private void onlineCalculateRequestStartPoint(@NonNull LatLon start,
	                                              @NonNull LatLon finish,
	                                              @NonNull OnResultCallback<List<Location>> callback) {
		String baseUrl = "https://maptile.osmand.net/routing/route?routeMode=car";
		String fullURL = baseUrl + "&" + formatPointString(start) + "&" + formatPointString(finish);
		try {
			OnlineRoutingHelper helper = app.getOnlineRoutingHelper();
			String response = helper.makeRequest(fullURL);
			callback.onResult(parseOnlineCalculationResponse(response));
		} catch (Exception e) {
			onErrorReceived(e.getMessage());
		}
	}

	@NonNull
	private String formatPointString(@NonNull LatLon location) {
		return "points=" + location.getLatitude() + "," + location.getLongitude();
	}

	@NonNull
	private List<Location> parseOnlineCalculationResponse(@NonNull String response) throws JSONException {
		List<Location> result = new ArrayList<>();
		JSONObject fullJSON = new JSONObject(response);
		JSONArray features = fullJSON.getJSONArray("features");
		for (int i = 0; i < features.length(); i++) {
			JSONObject feature = features.getJSONObject(i);
			JSONObject geometry = feature.getJSONObject("geometry");
			String type = geometry.getString("type");
			if (Objects.equals(type, "LineString")) {
				JSONArray coordinates = geometry.getJSONArray("coordinates");
				for (int j = 0; j < coordinates.length(); j++) {
					JSONArray coordinate = coordinates.getJSONArray(j);
					parseAndAddLocation(result, coordinate);
				}
			} else if (Objects.equals(type, "Point")) {
				JSONArray coordinates = geometry.getJSONArray("coordinates");
				parseAndAddLocation(result, coordinates);
			}
		}
		return result;
	}

	private void parseAndAddLocation(@NonNull List<Location> locations, @NonNull JSONArray coordinate) throws JSONException {
		if (coordinate.length() >= 2) {
			WptPt wpt = new WptPt();
			wpt.lat = coordinate.getDouble(1);
			wpt.lon = coordinate.getDouble(0);
			locations.add(RouteProvider.createLocation(wpt));
		}
	}

	private void onErrorReceived(@Nullable String error) {
		app.runInUIThread(() -> {
			app.showToastMessage(error);
			loadingMapsInProgress = false;
			askRefreshDialog();
		});
	}

	@NonNull
	public String getDownloadButtonTitle() {
		double downloadSizeMb = 0.0d;
		for (DownloadItem downloadItem : itemsSelectionHelper.getSelectedItems()) {
			downloadSizeMb += downloadItem.getSizeToDownloadInMb();
		}
		String size = DownloadItem.getFormattedMb(app, downloadSizeMb);
		String btnTitle = app.getString(R.string.shared_string_download);
		boolean displaySize = !loadingMapsInProgress && downloadSizeMb > 0;
		return displaySize ? app.getString(R.string.ltr_or_rtl_combine_via_dash, btnTitle, size) : btnTitle;
	}

	public boolean isDownloadButtonEnabled() {
		return itemsSelectionHelper.hasSelectedItems() && !loadingMapsInProgress;
	}

	public void onDownloadButtonClicked(@NonNull MapActivity mapActivity) {
		mapActivity.getMapLayers().getMapActionsHelper().stopNavigationWithoutConfirm();
		mapActivity.getMapRouteInfoMenu().resetRouteCalculation();

		List<IndexItem> indexes = new ArrayList<>();
		for (DownloadItem item : itemsSelectionHelper.getSelectedItems()) {
			if (item instanceof IndexItem) {
				IndexItem index = (IndexItem) item;
				indexes.add(index);
			}
		}
		IndexItem[] indexesArray = new IndexItem[indexes.size()];
		new DownloadValidationManager(app).startDownload(mapActivity, indexes.toArray(indexesArray));

		Intent newIntent = new Intent(mapActivity, app.getAppCustomization().getDownloadActivity());
		newIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		mapActivity.startActivity(newIntent);
	}

	public void onItemClicked(@NonNull DownloadItem downloadItem) {
		boolean selected = itemsSelectionHelper.isItemSelected(downloadItem);
		itemsSelectionHelper.onItemsSelected(Collections.singleton(downloadItem), !selected);
	}

	public void onSelectAllClicked() {
		if (itemsSelectionHelper.isAllItemsSelected()) {
			itemsSelectionHelper.clearSelectedItems();
		} else {
			itemsSelectionHelper.selectAllItems();
		}
	}

	public boolean isAllItemsSelected() {
		return itemsSelectionHelper.isAllItemsSelected();
	}

	public boolean isOnlineCalculationRequested() {
		return onlineCalculationRequested;
	}

	public boolean isLoadingInProgress() {
		return loadingMapsInProgress;
	}

	@Override
	public void onUpdatedIndexesList() {
		loadingMapsInProgress = false;
		updateSelectionHelper();
		askRefreshDialog();
	}

	public void askRefreshDialog() {
		app.runInUIThread(() -> {
			app.getDialogManager().askRefreshDialogCompletely(PROCESS_ID);
		});
	}

	private void runAsync(@NonNull Runnable runnable) {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... voids) {
				runnable.run();
				return null;
			}
		}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	}

	public static void showDialog(@NonNull FragmentActivity activity) {
		OsmandApplication app = (OsmandApplication) activity.getApplicationContext();
		DialogManager dialogManager = app.getDialogManager();
		dialogManager.register(PROCESS_ID, new RequiredMapsController(app));
		RequiredMapsFragment.showInstance(activity.getSupportFragmentManager());
	}
}
