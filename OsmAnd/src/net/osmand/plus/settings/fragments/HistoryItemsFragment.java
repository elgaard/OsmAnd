package net.osmand.plus.settings.fragments;

import static net.osmand.plus.UiUtilities.CompoundButtonType.TOOLBAR;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.osmand.AndroidUtils;
import net.osmand.Location;
import net.osmand.plus.ColorUtilities;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmAndLocationProvider.OsmAndCompassListener;
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.UiUtilities.DialogButtonType;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.BaseOsmAndDialogFragment;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.fragments.HistoryAdapter.OnItemSelectedListener;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class HistoryItemsFragment extends BaseOsmAndDialogFragment implements OnItemSelectedListener, OsmAndCompassListener, OsmAndLocationListener {

	protected OsmandApplication app;
	protected OsmandSettings settings;

	protected final List<Object> items = new ArrayList<>();
	protected final List<Object> selectedItems = new ArrayList<>();
	protected final Map<Integer, List<?>> itemsGroups = new HashMap<>();

	protected View deleteButton;
	protected View selectAllButton;
	protected ImageView shareButton;
	protected HistoryAdapter adapter;
	protected RecyclerView recyclerView;
	protected View warningCard;

	private Float heading;
	private Location location;
	private boolean locationUpdateStarted;
	private boolean compassUpdateAllowed = true;
	protected boolean nightMode;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		app = getMyApplication();
		settings = app.getSettings();
		nightMode = !app.getSettings().isLightContent();
		updateHistoryItems();
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		MapActivity mapActivity = (MapActivity) requireActivity();
		View view = UiUtilities.getInflater(mapActivity, nightMode).inflate(R.layout.history_preferences_fragment, container, false);

		recyclerView = view.findViewById(R.id.list);
		recyclerView.setLayoutManager(new LinearLayoutManager(mapActivity));
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
				compassUpdateAllowed = newState == RecyclerView.SCROLL_STATE_IDLE;
			}
		});

		adapter = new HistoryAdapter(app, this, nightMode);
		adapter.updateSettingsItems(items, itemsGroups, selectedItems);
		recyclerView.setAdapter(adapter);

		setupToolbar(view);
		setupButtons(view);
		setupWarningCard(view);
		updateDisabledItems();

		return view;
	}

	protected abstract void shareItems();

	protected abstract void updateHistoryItems();

	protected abstract void deleteSelectedItems();

	protected abstract boolean isHistoryEnabled();

	public void clearItems() {
		items.clear();
		itemsGroups.clear();
		selectedItems.clear();
	}

	protected void setupToolbar(@NonNull View view) {
		View appbar = view.findViewById(R.id.appbar);
		ViewCompat.setElevation(appbar, 5.0f);

		ImageView closeButton = appbar.findViewById(R.id.close_button);
		closeButton.setImageDrawable(getIcon(R.drawable.ic_action_close));
		closeButton.setOnClickListener(v -> dismiss());

		shareButton = appbar.findViewById(R.id.action_button_icon);
		shareButton.setOnClickListener(v -> shareItems());
		updateToolbarSwitch(appbar);
	}

	protected void updateToolbarSwitch(@NonNull View view) {
		boolean checked = isHistoryEnabled();

		if (checked) {
			shareButton.setImageDrawable(getIcon(R.drawable.ic_action_export));
		} else {
			int color = ContextCompat.getColor(app, R.color.active_buttons_and_links_text_light);
			int colorWithAlpha = ColorUtilities.getColorWithAlpha(color, 0.5f);
			shareButton.setImageDrawable(getPaintedContentIcon(R.drawable.ic_action_export, colorWithAlpha));
		}
		shareButton.setEnabled(checked);

		View selectableView = view.findViewById(R.id.selectable_item);

		SwitchCompat switchView = selectableView.findViewById(R.id.switchWidget);
		switchView.setChecked(checked);
		UiUtilities.setupCompoundButton(switchView, nightMode, TOOLBAR);

		TextView title = selectableView.findViewById(R.id.switchButtonText);
		title.setText(checked ? R.string.shared_string_enabled : R.string.shared_string_disabled);

		Drawable drawable = UiUtilities.getColoredSelectableDrawable(app, ColorUtilities.getActiveColor(app, nightMode), 0.3f);
		AndroidUtils.setBackground(selectableView, drawable);

		int color = checked ? ColorUtilities.getActiveColor(app, nightMode) : ContextCompat.getColor(app, R.color.preference_top_switch_off);
		View switchContainer = view.findViewById(R.id.toolbar_switch_container);
		AndroidUtils.setBackground(switchContainer, new ColorDrawable(color));
	}

	protected void setupButtons(@NonNull View view) {
		View buttonsContainer = view.findViewById(R.id.buttons_container);
		buttonsContainer.setBackgroundColor(AndroidUtils.getColorFromAttr(view.getContext(), R.attr.bg_color));

		deleteButton = view.findViewById(R.id.right_bottom_button);
		deleteButton.setOnClickListener(v -> {
			deleteSelectedItems();
			updateHistoryItems();
			updateButtonsState();
			adapter.notifyDataSetChanged();
		});
		selectAllButton = view.findViewById(R.id.dismiss_button);
		selectAllButton.setOnClickListener(v -> {
			for (List<?> items : itemsGroups.values()) {
				selectedItems.addAll(items);
			}
			adapter.notifyDataSetChanged();
		});

		UiUtilities.setupDialogButton(nightMode, selectAllButton, DialogButtonType.SECONDARY, R.string.shared_string_select_all);
		UiUtilities.setupDialogButton(nightMode, deleteButton, DialogButtonType.PRIMARY, R.string.shared_string_delete);

		AndroidUiHelper.updateVisibility(deleteButton, true);
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.buttons_divider), true);
	}

	protected void setupWarningCard(@NonNull View view) {
		warningCard = view.findViewById(R.id.disabled_history_card);
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.settings_button_container), false);
	}

	protected void updateDisabledItems() {
		boolean checked = isHistoryEnabled();
		AndroidUiHelper.updateVisibility(recyclerView, checked);
		AndroidUiHelper.updateVisibility(warningCard, !checked);
		updateButtonsState();
	}

	protected void updateButtonsState() {
		boolean checked = isHistoryEnabled();
		selectAllButton.setEnabled(checked);
		deleteButton.setEnabled(checked && !selectedItems.isEmpty());
	}

	@Override
	public void onItemSelected(Object item, boolean selected) {
		if (selected) {
			selectedItems.add(item);
		} else {
			selectedItems.remove(item);
		}
		updateButtonsState();
	}

	@Override
	public void onCategorySelected(List<Object> items, boolean selected) {
		if (selected) {
			selectedItems.addAll(items);
		} else {
			selectedItems.removeAll(items);
		}
		updateButtonsState();
	}

	@Override
	public void onResume() {
		super.onResume();
		startLocationUpdate();
	}

	@Override
	public void onPause() {
		super.onPause();
		stopLocationUpdate();
	}

	@Override
	public void updateLocation(Location location) {
		if (!MapUtils.areLatLonEqual(this.location, location)) {
			this.location = location;
			updateLocationUi();
		}
	}

	@Override
	public void updateCompassValue(float value) {
		// 99 in next line used to one-time initialize arrows (with reference vs. fixed-north direction)
		// on non-compass devices
		float lastHeading = heading != null ? heading : 99;
		heading = value;
		if (Math.abs(MapUtils.degreesDiff(lastHeading, heading)) > 5) {
			updateLocationUi();
		} else {
			heading = lastHeading;
		}
	}

	private void updateLocationUi() {
		if (compassUpdateAllowed && adapter != null) {
			app.runInUIThread(() -> {
				if (location == null) {
					location = app.getLocationProvider().getLastKnownLocation();
				}
				adapter.notifyDataSetChanged();
			});
		}
	}

	private void startLocationUpdate() {
		OsmandApplication app = getMyApplication();
		if (app != null && !locationUpdateStarted) {
			locationUpdateStarted = true;
			OsmAndLocationProvider locationProvider = app.getLocationProvider();
			locationProvider.removeCompassListener(locationProvider.getNavigationInfo());
			locationProvider.addCompassListener(this);
			locationProvider.addLocationListener(this);
			updateLocationUi();
		}
	}

	private void stopLocationUpdate() {
		OsmandApplication app = getMyApplication();
		if (app != null && locationUpdateStarted) {
			locationUpdateStarted = false;
			OsmAndLocationProvider locationProvider = app.getLocationProvider();
			locationProvider.removeLocationListener(this);
			locationProvider.removeCompassListener(this);
			locationProvider.addCompassListener(locationProvider.getNavigationInfo());
		}
	}
}