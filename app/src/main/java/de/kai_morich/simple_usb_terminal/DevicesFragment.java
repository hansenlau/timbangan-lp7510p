package de.kai_morich.simple_usb_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.Locale;

public class DevicesFragment extends ListFragment {

    static class ListItem {
        UsbDevice device;
        int port;
        UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    private final ArrayList<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 9600;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        listAdapter = new ArrayAdapter<ListItem>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                ListItem item = listItems.get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);

                // --- Dummy device entry ---
                if (item.device == null) {
                    text1.setText("[TEST] Dummy Device");
                    text2.setText("Untuk testing tanpa USB");
                } else if (item.driver == null) {
                    text1.setText("<no driver>");
                    text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X",
                            item.device.getVendorId(), item.device.getProductId()));
                } else if (item.driver.getPorts().size() == 1) {
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", ""));
                    text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X",
                            item.device.getVendorId(), item.device.getProductId()));
                } else {
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", "") + ", Port " + item.port);
                    text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X",
                            item.device.getVendorId(), item.device.getProductId()));
                }
                return view;
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setEmptyText("<no USB devices found>");
        ((TextView) getListView().getEmptyView()).setTextSize(18);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.refresh) {
            refresh();
            return true;
        } else if (id == R.id.baud_rate) {
            final String[] baudRates = getResources().getStringArray(R.array.baud_rates);
            int pos = java.util.Arrays.asList(baudRates).indexOf(String.valueOf(baudRate));
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Baud rate");
            builder.setSingleChoiceItems(baudRates, pos, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int item) {
                    baudRate = Integer.parseInt(baudRates[item]);
                    dialog.dismiss();
                }
            });
            builder.create().show();
            return true;
        } else if (id == R.id.developer_mode) {
            boolean currentlyActive = isDeveloperModeActive();
            if (currentlyActive) {
                // Nonaktifkan tanpa password
                setDeveloperModeActive(false);
                refresh();
                Toast.makeText(getActivity(), "Developer mode dinonaktifkan", Toast.LENGTH_SHORT).show();
            } else {
                // Aktifkan dengan password
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle("Developer Mode");
                builder.setMessage("Masukkan password:");
                final android.widget.EditText input = new android.widget.EditText(getActivity());
                input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                builder.setView(input);
                builder.setPositiveButton("OK", (dialog, which) -> {
                    String val = input.getText().toString();
                    if ("12345qwerty".equals(val)) {
                        setDeveloperModeActive(true);
                        refresh();
                        Toast.makeText(getActivity(), "Developer mode aktif", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getActivity(), "Password salah", Toast.LENGTH_SHORT).show();
                    }
                });
                builder.setNegativeButton("Batal", (dialog, which) -> dialog.cancel());
                builder.show();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private boolean isDeveloperModeActive() {
        return getActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("developer_mode", false);
    }

    private void setDeveloperModeActive(boolean active) {
        getActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("developer_mode", active)
                .apply();
    }

    void refresh() {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();

        // --- Dummy device hanya muncul saat developer mode aktif ---
        if (isDeveloperModeActive()) {
            listItems.add(new ListItem(null, 0, null));
        }

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if (driver != null) {
                for (int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new ListItem(device, port, driver));
            } else {
                listItems.add(new ListItem(device, 0, null));
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        ListItem item = listItems.get(position - 1);

        // --- Dummy device: langsung masuk TerminalFragment tanpa USB ---
        if (item.device == null) {
            Bundle args = new Bundle();
            args.putInt("device", -1);
            args.putInt("port", 0);
            args.putInt("baud", baudRate);
            Fragment fragment = new TerminalFragment();
            fragment.setArguments(args);
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment, fragment, "terminal")
                    .addToBackStack(null)
                    .commit();
            return;
        }

        if (item.driver == null) {
            Toast.makeText(getActivity(), "no driver", Toast.LENGTH_SHORT).show();
        } else {
            Bundle args = new Bundle();
            args.putInt("device", item.device.getDeviceId());
            args.putInt("port", item.port);
            args.putInt("baud", baudRate);
            Fragment fragment = new TerminalFragment();
            fragment.setArguments(args);
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment, fragment, "terminal")
                    .addToBackStack(null)
                    .commit();
        }
    }
}