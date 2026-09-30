package de.kai_morich.simple_usb_terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

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

    private static final int REQUEST_EXPORT_USB_TREE = 4711;

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
        TextView tvVersion = header.findViewById(R.id.tvVersion);
        String versionName = "";
        try {
            versionName = getActivity().getPackageManager()
                    .getPackageInfo(getActivity().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        tvVersion.setText("Versi " + versionName);
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
        } else if (id == R.id.export_usb) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Toast.makeText(getActivity(), "Fitur ini butuh Android 5.0+", Toast.LENGTH_SHORT).show();
                return true;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                Toast.makeText(getActivity(), "Pilih drive USB tujuan", Toast.LENGTH_SHORT).show();
                startActivityForResult(intent, REQUEST_EXPORT_USB_TREE);
            } catch (Exception e) {
                Toast.makeText(getActivity(), "Tidak bisa membuka pemilih folder", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_USB_TREE && resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null)
                exportLast7DaysToTree(treeUri);
        }
    }

    /*
     * Export semua CSV (7 hari terakhir) ke drive USB pilihan admin lewat SAF.
     * Hanya menyalin (data asli di HP tidak diubah/dihapus). Terisolasi dari
     * jalur serial/timbangan sepenuhnya.
     */
    @SuppressLint("NewApi") // hanya dipanggil setelah cek SDK_INT >= LOLLIPOP di menu
    private void exportLast7DaysToTree(final Uri treeUri) {
        final Activity activity = getActivity();
        if (activity == null) return;
        final Context appCtx = activity.getApplicationContext();

        File srcDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        final long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000; // 7 hari
        final ArrayList<File> toCopy = new ArrayList<>();
        if (srcDir != null && srcDir.exists()) {
            File[] files = srcDir.listFiles();
            if (files != null)
                for (File f : files)
                    if (f.isFile()
                            && f.getName().toLowerCase(Locale.US).endsWith(".csv")
                            && f.lastModified() >= cutoff)
                        toCopy.add(f);
        }
        if (toCopy.isEmpty()) {
            Toast.makeText(activity, "Tidak ada data CSV 7 hari terakhir", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Menyalin " + toCopy.size() + " file ke USB...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            int ok = 0, fail = 0;
            try {
                DocumentFile pickedDir = DocumentFile.fromTreeUri(appCtx, treeUri);
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String folderName = "Timbangan_"
                        + Build.MODEL.replaceAll("[^A-Za-z0-9_-]", "") + "_" + stamp;
                DocumentFile destDir = pickedDir != null ? pickedDir.createDirectory(folderName) : null;
                if (destDir == null) {
                    postToast(activity, "Gagal membuat folder di USB");
                    return;
                }
                ContentResolver resolver = appCtx.getContentResolver();
                byte[] buffer = new byte[8192];
                for (File f : toCopy) {
                    DocumentFile destFile = destDir.createFile("text/csv", f.getName());
                    if (destFile == null) { fail++; continue; }
                    try (InputStream in = new FileInputStream(f);
                         OutputStream out = resolver.openOutputStream(destFile.getUri())) {
                        if (out == null) { fail++; continue; }
                        int len;
                        while ((len = in.read(buffer)) != -1)
                            out.write(buffer, 0, len);
                        out.flush();
                        ok++;
                    } catch (Exception e) {
                        fail++;
                    }
                }
                postToast(activity, "Selesai: " + ok + " file tersalin"
                        + (fail > 0 ? ", " + fail + " gagal" : ""));
            } catch (Exception e) {
                postToast(activity, "Gagal export: " + e.getMessage());
            }
        }).start();
    }

    private void postToast(final Activity activity, final String msg) {
        if (activity == null) return;
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
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