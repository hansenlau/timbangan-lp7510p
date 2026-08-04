package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Environment;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.FileInputStream;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    // --- Tab enum ---
    private enum ActiveTab { PRODUKSI, BS_POTONGAN, BS_ROLL, RONTOKAN }

    private final Handler mainLooper;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    // --- UI Timbangan ---
    private TextView tvStatus;
    private EditText etNoProduksi;
    private Button btnStartSession, btnExport, btnDeleteLast;
    private RecyclerView rvData;
    private TextView tvTotalWeight;

    // --- Tab buttons ---
    private Button btnTabProduksi, btnTabBsPotongan, btnTabBsRoll, btnTabRontokan;

    // --- Testing & utility buttons ---
    private Button btnAddDummy;
    private Button btnSetNoUrut;

    // --- State ---
    private Connected connected = Connected.False;
    private boolean initialStart = true;

    // --- Auto-reconnect ---
    private boolean reconnecting = false;
    private static final long RECONNECT_DELAY_MS = 3000;
    // identitas USB timbangan; dipakai reconnect kalau deviceId berubah akibat enumerasi ulang
    private int usbVendorId = -1, usbProductId = -1;

    private boolean sessionActive = false;
    private String currentNoProduksi = "";

    // --- Active tab state ---
    private ActiveTab activeTab = ActiveTab.PRODUKSI;

    // --- Per-kategori row counter ---
    private int rowCounter = 0;
    private int bsPotonganCounter = 0;
    private int bsRollCounter = 0;
    private int rontokanCounter = 0;

    // --- Per-kategori data lists ---
    private final StringBuilder readBuffer = new StringBuilder();
    // true kalau blok print LP7510P yang sedang berjalan sudah menghasilkan satu row
    private boolean blockCommitted = false;
    private final List<WeightRow> weightRows = new ArrayList<>();
    private final List<WeightRow> bsPotonganRows = new ArrayList<>();
    private final List<WeightRow> bsRollRows = new ArrayList<>();
    private final List<WeightRow> rontokanRows = new ArrayList<>();

    private WeightAdapter weightAdapter;

    // --- Active tab label ---
    private TextView tvActiveTabLabel;

    // --- Tab colors per kategori ---
    private static final int COLOR_PRODUKSI     = 0xFF2E7D32; // hijau
    private static final int COLOR_BS_POTONGAN  = 0xFFE65100; // orange
    private static final int COLOR_BS_ROLL      = 0xFFC62828; // merah
    private static final int COLOR_RONTOKAN     = 0xFF6A1B9A; // ungu
    private static final int COLOR_TAB_INACTIVE = 0xFF9E9E9E; // abu-abu

    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        requireActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            requireActivity().startService(new Intent(getActivity(), SerialService.class));
        ContextCompat.registerReceiver(requireActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
        cleanupOldFiles();
    }

    @Override
    public void onStop() {
        requireActivity().unregisterReceiver(broadcastReceiver);
        if(service != null && !requireActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        requireActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { requireActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            requireActivity().runOnUiThread(this::connect);
        } else if (service != null && connected == Connected.False && deviceId != -1) {
            // kembali ke depan setelah link putus saat background -> sambung ulang
            scheduleReconnect();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            requireActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        tvStatus        = view.findViewById(R.id.tvStatus);
        etNoProduksi    = view.findViewById(R.id.etNoProduksi);
        btnStartSession = view.findViewById(R.id.btnStartSession);
        btnExport       = view.findViewById(R.id.btnExport);
        btnDeleteLast   = view.findViewById(R.id.btnDeleteLast);
        tvTotalWeight   = view.findViewById(R.id.tvTotalWeight);
        rvData          = view.findViewById(R.id.rvDataTimbangan);

        btnTabProduksi   = view.findViewById(R.id.btnTabProduksi);
        btnTabBsPotongan = view.findViewById(R.id.btnTabBsPotongan);
        btnTabBsRoll     = view.findViewById(R.id.btnTabBsRoll);
        btnTabRontokan   = view.findViewById(R.id.btnTabRontokan);
        btnAddDummy      = view.findViewById(R.id.btnAddDummy);
        btnSetNoUrut     = view.findViewById(R.id.btnSetNoUrut);
        tvActiveTabLabel = view.findViewById(R.id.tvActiveTabLabel);

        // Show/hide btnAddDummy berdasarkan developer mode
        boolean devMode = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("developer_mode", false);
        btnAddDummy.setVisibility(devMode ? View.VISIBLE : View.GONE);

        rvData.setLayoutManager(new LinearLayoutManager(getContext()));
        weightAdapter = new WeightAdapter(weightRows);
        rvData.setAdapter(weightAdapter);

        // Set initial tab state (Produksi aktif)
        updateTabUI();

        // --- Tab click listeners ---
        btnTabProduksi.setOnClickListener(v -> switchTab(ActiveTab.PRODUKSI));
        btnTabBsPotongan.setOnClickListener(v -> switchTab(ActiveTab.BS_POTONGAN));
        btnTabBsRoll.setOnClickListener(v -> switchTab(ActiveTab.BS_ROLL));
        btnTabRontokan.setOnClickListener(v -> switchTab(ActiveTab.RONTOKAN));

        // --- Add Dummy (testing only) ---
        btnAddDummy.setOnClickListener(v -> {
            if (!sessionActive) {
                Toast.makeText(getContext(), "Start session dulu", Toast.LENGTH_SHORT).show();
                return;
            }
            double randomKg = 1.0 + (Math.random() * 98.99);
            String dummyLine = String.format(Locale.US, "Gross %.2fkg", randomKg);
            addWeightRow(dummyLine, System.currentTimeMillis());
        });

        // --- Set No Urut ---
        btnSetNoUrut.setOnClickListener(v -> {
            if (!sessionActive) {
                Toast.makeText(getContext(), "Start session dulu", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!getActiveList().isEmpty()) {
                Toast.makeText(getContext(), "Tidak bisa diubah, sudah ada data di tab ini", Toast.LENGTH_SHORT).show();
                return;
            }
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
            builder.setTitle("Set No Urut Awal");
            final android.widget.EditText input = new android.widget.EditText(requireContext());
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setHint("Contoh: 7");
            builder.setView(input);
            builder.setPositiveButton("OK", (dialog, which) -> {
                String val = input.getText().toString().trim();
                if (val.isEmpty()) return;
                int startIndex = Integer.parseInt(val);
                if (startIndex < 1) {
                    Toast.makeText(getContext(), "No urut harus lebih dari 0", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Set counter ke startIndex - 1 supaya baris pertama yang masuk = startIndex
                switch (activeTab) {
                    case PRODUKSI:    rowCounter = startIndex - 1;        break;
                    case BS_POTONGAN: bsPotonganCounter = startIndex - 1; break;
                    case BS_ROLL:     bsRollCounter = startIndex - 1;     break;
                    case RONTOKAN:    rontokanCounter = startIndex - 1;   break;
                }
                Toast.makeText(getContext(), "No urut awal diset ke " + startIndex, Toast.LENGTH_SHORT).show();
            });
            builder.setNegativeButton("Batal", (dialog, which) -> dialog.cancel());
            builder.show();
        });

        // --- Start session ---
        btnStartSession.setOnClickListener(v -> {
            String noProd = etNoProduksi.getText().toString().trim();
            if(noProd.isEmpty()) {
                Toast.makeText(getContext(), "Isi No Produksi dulu", Toast.LENGTH_SHORT).show();
                return;
            }
            currentNoProduksi = noProd;
            sessionActive = true;
            rowCounter = 0;
            bsPotonganCounter = 0;
            bsRollCounter = 0;
            rontokanCounter = 0;
            weightRows.clear();
            bsPotonganRows.clear();
            bsRollRows.clear();
            rontokanRows.clear();
            activeTab = ActiveTab.PRODUKSI;
            weightAdapter.swapList(weightRows);
            weightAdapter.notifyDataSetChanged();
            updateTabUI();
            updateTotalWeight();
            etNoProduksi.setEnabled(false);
            btnStartSession.setEnabled(false);
            tvStatus.setText("Session aktif: " + currentNoProduksi);
        });

        // --- Export ---
        btnExport.setOnClickListener(v -> {
            boolean allEmpty = weightRows.isEmpty()
                    && bsPotonganRows.isEmpty()
                    && bsRollRows.isEmpty()
                    && rontokanRows.isEmpty();
            if (allEmpty) {
                Toast.makeText(getContext(), "Belum ada data untuk di-export", Toast.LENGTH_SHORT).show();
                return;
            }
            exportCsv();
        });

        // --- Delete Last (per tab aktif) ---
        btnDeleteLast.setOnClickListener(v -> {
            List<WeightRow> activeList = getActiveList();
            if (!activeList.isEmpty()) {
                int last = activeList.size() - 1;
                activeList.remove(last);
                weightAdapter.notifyItemRemoved(last);
                decrementActiveCounter();
                updateTotalWeight();
            }
        });

        return view;
    }

    /*
     * Tab helpers
     */
    private void switchTab(ActiveTab tab) {
        activeTab = tab;
        weightAdapter.swapList(getActiveList());
        weightAdapter.notifyDataSetChanged();
        updateTabUI();
        updateTotalWeight();
        // Scroll ke bawah list tab yang baru dibuka
        if (!getActiveList().isEmpty())
            rvData.scrollToPosition(getActiveList().size() - 1);
    }

    private List<WeightRow> getActiveList() {
        switch (activeTab) {
            case BS_POTONGAN: return bsPotonganRows;
            case BS_ROLL:     return bsRollRows;
            case RONTOKAN:    return rontokanRows;
            default:          return weightRows;
        }
    }

    private void updateTabUI() {
        // Tentukan warna aktif berdasarkan tab
        int activeColor;
        String labelText;
        switch (activeTab) {
            case BS_POTONGAN:
                activeColor = COLOR_BS_POTONGAN;
                labelText   = "BS POTONGAN";
                break;
            case BS_ROLL:
                activeColor = COLOR_BS_ROLL;
                labelText   = "BS ROLL";
                break;
            case RONTOKAN:
                activeColor = COLOR_RONTOKAN;
                labelText   = "RONTOKAN";
                break;
            default: // PRODUKSI
                activeColor = COLOR_PRODUKSI;
                labelText   = "PRODUKSI";
                break;
        }

        // Update label header
        if (tvActiveTabLabel != null) {
            tvActiveTabLabel.setText(labelText);
            tvActiveTabLabel.setBackgroundColor(activeColor);
        }

        // Update tombol tab: aktif = warna kategori, non-aktif = abu-abu
        btnTabProduksi.setBackgroundColor(
                activeTab == ActiveTab.PRODUKSI    ? COLOR_PRODUKSI    : COLOR_TAB_INACTIVE);
        btnTabBsPotongan.setBackgroundColor(
                activeTab == ActiveTab.BS_POTONGAN ? COLOR_BS_POTONGAN : COLOR_TAB_INACTIVE);
        btnTabBsRoll.setBackgroundColor(
                activeTab == ActiveTab.BS_ROLL     ? COLOR_BS_ROLL     : COLOR_TAB_INACTIVE);
        btnTabRontokan.setBackgroundColor(
                activeTab == ActiveTab.RONTOKAN    ? COLOR_RONTOKAN    : COLOR_TAB_INACTIVE);

        // Disable tombol tab yang sedang aktif
        btnTabProduksi.setEnabled(activeTab   != ActiveTab.PRODUKSI);
        btnTabBsPotongan.setEnabled(activeTab != ActiveTab.BS_POTONGAN);
        btnTabBsRoll.setEnabled(activeTab     != ActiveTab.BS_ROLL);
        btnTabRontokan.setEnabled(activeTab   != ActiveTab.RONTOKAN);
    }

    private void setTabActive(Button btn, boolean isActive) {
        // Kept for compatibility — not used directly anymore
        btn.setEnabled(!isActive);
    }

    private void decrementActiveCounter() {
        switch (activeTab) {
            case PRODUKSI:    if (rowCounter > 0)         rowCounter--;         break;
            case BS_POTONGAN: if (bsPotonganCounter > 0)  bsPotonganCounter--;  break;
            case BS_ROLL:     if (bsRollCounter > 0)      bsRollCounter--;      break;
            case RONTOKAN:    if (rontokanCounter > 0)     rontokanCounter--;    break;
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        // --- Dummy device: skip USB connection entirely ---
        if (deviceId == -1) {
            connected = Connected.True;
            status("[TEST MODE] Dummy device - tidak ada USB");
            return;
        }

        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        // Reconnect: kalau device enumerasi ulang, deviceId berubah -> cari via vendor/product
        if(device == null && usbVendorId != -1) {
            for(UsbDevice v : usbManager.getDeviceList().values())
                if(v.getVendorId() == usbVendorId && v.getProductId() == usbProductId) {
                    device = v;
                    deviceId = v.getDeviceId(); // pakai id baru
                    break;
                }
        }
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        // Ingat identitas device untuk reconnect berikutnya
        usbVendorId = device.getVendorId();
        usbProductId = device.getProductId();
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(requireActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(requireActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(requireActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        if(service != null)
            service.disconnect();
        usbSerialPort = null;
    }

    void status(String str) {
        if(tvStatus != null) {
            tvStatus.setText(str);
        }
    }


    private void cleanupOldFiles() {
        new Thread(() -> {
            try {
                File dir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || !dir.exists()) return;
                long threshold = 60L * 24 * 60 * 60 * 1000; // 60 hari (PRODUCTION)
                long cutoff = System.currentTimeMillis() - threshold;
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (f.isFile() && f.lastModified() < cutoff) {
                        f.delete();
                    }
                }
            } catch (Exception ignored) {
                // Cleanup gagal = tidak apa-apa, app tetap jalan normal
            }
        }).start();
    }

    /*
     * Export CSV — 2 file sekaligus
     */
    private void exportCsv() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        // ---- File 1: Produksi (existing logic) ----
        if (!weightRows.isEmpty()) {
            String fileName = currentNoProduksi + "_produksi_" + timeStamp + ".csv";
            writeCsvToDownloads(fileName, buildProduksiCsv());
        }

        // ---- File 2: Waste ----
        String wasteFileName = currentNoProduksi + "_waste_" + timeStamp + ".csv";
        writeCsvToDownloads(wasteFileName, buildWasteCsv());

        // ---- Reset session ----
        sessionActive = false;
        etNoProduksi.setEnabled(true);
        btnStartSession.setEnabled(true);
        weightRows.clear();
        bsPotonganRows.clear();
        bsRollRows.clear();
        rontokanRows.clear();
        rowCounter = 0;
        bsPotonganCounter = 0;
        bsRollCounter = 0;
        rontokanCounter = 0;
        activeTab = ActiveTab.PRODUKSI;
        weightAdapter.swapList(weightRows);
        weightAdapter.notifyDataSetChanged();
        updateTabUI();
        updateTotalWeight();
        tvStatus.setText("Export selesai. Session berakhir.");
    }

    private String buildProduksiCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("NoProduksi;NoUrut;Berat(kg);Raw;Timestamp\n");
        for (WeightRow r : weightRows) {
            sb.append(currentNoProduksi).append(";")
                    .append(r.index).append(";")
                    .append(extractWeight(r.value)).append(";")
                    .append(r.value).append(";")
                    .append(r.timeFull).append("\n");
        }
        return sb.toString();
    }

    private String buildWasteCsv() {
        double totalBsPotongan = sumList(bsPotonganRows);
        double totalBsRoll     = sumList(bsRollRows);
        double totalRontokan   = sumList(rontokanRows);

        return "no produksi,BS Potongan,BS Roll,Rontokan\n" +
                currentNoProduksi + "," +
                String.format(Locale.US, "%.2f", totalBsPotongan) + "," +
                String.format(Locale.US, "%.2f", totalBsRoll) + "," +
                String.format(Locale.US, "%.2f", totalRontokan) + "\n";
    }

    private double sumList(List<WeightRow> list) {
        double total = 0;
        for (WeightRow r : list) {
            try {
                total += Double.parseDouble(extractWeight(r.value));
            } catch (NumberFormatException ignored) {}
        }
        return total;
    }

    private void writeCsvToDownloads(String fileName, String content) {
        // Tulis ke app-private dir dulu
        File dir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null && !dir.exists()) dir.mkdirs();
        File file = new File(dir, fileName);

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            bw.write(content);
            bw.flush();
        } catch (IOException e) {
            Toast.makeText(getContext(), "Gagal tulis file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // Copy ke public Downloads via MediaStore
        try {
            ContentResolver resolver = requireContext().getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = resolver.openOutputStream(uri)) {
                    if (out != null) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
            Toast.makeText(getContext(), "Tersimpan: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Gagal copy ke Download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        connected = Connected.True;
        status("connected");
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        ArrayDeque<Long> timestamps = new ArrayDeque<>();
        datas.add(data);
        timestamps.add(System.currentTimeMillis());
        onSerialRead(datas, timestamps);
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas, ArrayDeque<Long> timestamps) {
        Long[] tsArray = timestamps.toArray(new Long[0]);
        byte[][] dataArray = datas.toArray(new byte[0][]);

        for (int i = 0; i < dataArray.length; i++) {
            long receivedAt = tsArray[i];
            String msg = new String(dataArray[i]);
            readBuffer.append(msg);
            int idx;
            while ((idx = readBuffer.indexOf("\n")) != -1) {
                String line = readBuffer.substring(0, idx).trim();
                readBuffer.delete(0, idx + 1);
                if (!line.isEmpty()) {
                    processScaleLine(line, receivedAt);
                }
            }
        }
    }

    /*
     * Parser blok print LP7510P.
     * Tanpa tare : Date: / Time: / Gross ...kg
     * Dengan tare: Date: / Time: / Net ...kg / Tare ...kg / Gross ...kg
     * Yang direkam: Net kalau ada (setelah tare), selain itu Gross.
     */
    private void processScaleLine(String line, long receivedAt) {
        String normalized = line.replaceAll("\\s+", " ");
        if (normalized.startsWith("Date:")) {
            blockCommitted = false; // blok print baru dimulai
            return;
        }
        if (normalized.startsWith("Time:") || normalized.startsWith("Tare"))
            return;
        if (blockCommitted)
            return; // Gross setelah Net di blok tare — sudah terekam
        if (normalized.startsWith("Net") || normalized.startsWith("Gross")) {
            blockCommitted = true;
            if (sessionActive)
                addWeightRow(normalized, receivedAt);
        }
    }

    private void addWeightRow(String line, long receivedAt) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(receivedAt));
        String timeFull = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date(receivedAt));

        List<WeightRow> targetList;
        int index;

        switch (activeTab) {
            case BS_POTONGAN:
                bsPotonganCounter++;
                index = bsPotonganCounter;
                targetList = bsPotonganRows;
                break;
            case BS_ROLL:
                bsRollCounter++;
                index = bsRollCounter;
                targetList = bsRollRows;
                break;
            case RONTOKAN:
                rontokanCounter++;
                index = rontokanCounter;
                targetList = rontokanRows;
                break;
            default: // PRODUKSI
                rowCounter++;
                index = rowCounter;
                targetList = weightRows;
                break;
        }

        WeightRow row = new WeightRow(index, line, time, timeFull);
        targetList.add(row);

        // Update UI hanya kalau tab yang menerima data adalah tab yang sedang aktif
        if (targetList == getActiveList()) {
            weightAdapter.notifyItemInserted(targetList.size() - 1);
            rvData.scrollToPosition(targetList.size() - 1);
            updateTotalWeight();
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("Koneksi terputus, mencoba sambung ulang...");
        disconnect();
        scheduleReconnect();
    }

    /*
     * Auto-reconnect: coba sambung ulang sendiri saat link putus, tanpa
     * kehilangan data session (weightRows dll. tetap tersimpan di fragment).
     */
    private void scheduleReconnect() {
        if (deviceId == -1) return;   // dummy/test mode, tidak ada USB
        if (reconnecting) return;     // sudah ada loop reconnect berjalan
        reconnecting = true;
        mainLooper.postDelayed(this::attemptReconnect, RECONNECT_DELAY_MS);
    }

    private void attemptReconnect() {
        if (deviceId == -1 || connected == Connected.True || !isResumed()) {
            reconnecting = false;
            return;
        }
        connect();
        if (connected == Connected.True) {
            reconnecting = false;
        } else {
            // gagal (mis. kabel belum tersambung) -> coba lagi
            mainLooper.postDelayed(this::attemptReconnect, RECONNECT_DELAY_MS);
        }
    }

    /*
     * Data & Adapter
     */
    private static class WeightRow {
        int index;
        String value;
        String time;
        String timeFull;

        WeightRow(int index, String value, String time, String timeFull) {
            this.index = index;
            this.value = value;
            this.time = time;
            this.timeFull = timeFull;
        }
    }

    private static class WeightAdapter extends RecyclerView.Adapter<WeightAdapter.VH> {

        private List<WeightRow> items;

        WeightAdapter(List<WeightRow> items) {
            this.items = items;
        }

        void swapList(List<WeightRow> newList) {
            this.items = newList;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvIndex, tvValue, tvTime;

            VH(View itemView) {
                super(itemView);
                tvIndex = itemView.findViewById(R.id.tvIndex);
                tvValue = itemView.findViewById(R.id.tvValue);
                tvTime  = itemView.findViewById(R.id.tvTime);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_weight_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            WeightRow row = items.get(position);
            holder.tvIndex.setText(String.valueOf(row.index));
            holder.tvValue.setText(row.value);
            holder.tvTime.setText(row.time);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // Contoh raw LP7510P: "Gross 40.05kg" atau "Net 20.05kg"
    private static final java.util.regex.Pattern WEIGHT_PATTERN =
            java.util.regex.Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)\\s*kg", java.util.regex.Pattern.CASE_INSENSITIVE);

    private String extractWeight(String raw) {
        if (raw == null) return "";
        java.util.regex.Matcher m = WEIGHT_PATTERN.matcher(raw);
        if (m.find()) {
            try {
                double val = Double.parseDouble(m.group(1));
                return String.format(Locale.US, "%.2f", val);
            } catch (NumberFormatException ignored) {
            }
        }
        return raw.trim();
    }

    private void updateTotalWeight() {
        double total = sumList(getActiveList());
        tvTotalWeight.setText(String.format(Locale.US, "Total: %.2f kg", total));
    }
}