package com.cordova.plugin;

// Aggiungi questo importo in cima al file
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.util.HashMap;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbManager;  // ← AGGIUNGI QUESTA RIGA
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class UsbExternalCamera extends CordovaPlugin {
    private static final String TAG = "UsbExternalCamera";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    
    private String externalCameraId;
    private CallbackContext frameCallback;
    private CallbackContext errorCallback;
    
    private int previewWidth = 1280;
    private int previewHeight = 720;
    private int previewFps = 30;
    
    private boolean isPreviewActive = false;
    private CallbackContext pendingOpenCallback; // Nuovo campo per memorizzare il callback
    private JSONArray pendingOpenArgs; // Nuovo campo per memorizzare gli argomenti
    
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "open":
                return openCamera(args, callbackContext);
            case "stopPreview":
                return stopPreview(callbackContext);
            case "takePhoto":
                return takePhoto(callbackContext);
            case "close":
                return closeCamera(callbackContext);
            case "listCameras":  // ← NUOVO COMANDO
                return listCameras(callbackContext);
            default:
                return false;
        }
    }

    private boolean openCamera(JSONArray args, CallbackContext callbackContext) throws JSONException {
        // RIMUOVI completamente il controllo permessi per USB cameras
        // if (!checkPermissions()) {
        //     requestPermissions();
        //     callbackContext.error("Camera permissions not granted");
        //     return true;
        // }

        JSONObject options = args.optJSONObject(0);
        if (options != null) {
            previewWidth = options.optInt("width", 1280);
            previewHeight = options.optInt("height", 720);
            previewFps = options.optInt("fps", 30);
            
            // ← NUOVO: Supporta cameraId specifico
            String requestedCameraId = options.optString("cameraId", null);
            if (requestedCameraId != null && !requestedCameraId.isEmpty()) {
                externalCameraId = requestedCameraId;
            }
        }
        
        frameCallback = callbackContext;
        
        cordova.getThreadPool().execute(() -> {
            try {
                initializeCamera();
            } catch (Exception e) {
                Log.e(TAG, "Error opening camera", e);
                if (frameCallback != null) {
                    frameCallback.error("Failed to open camera: " + e.getMessage());
                }
            }
        });
        
        return true;
    }
    
    // Metodo semplificato per controllo permessi USB
    private boolean checkUsbPermissions() {
        // Per fotocamere USB esterne, controlla solo i permessi USB
        UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
        return usbManager != null;
    }
    
    // RIMUOVI o commenta questi metodi se non servono più
    // private boolean checkPermissions() { ... }
    // private void requestPermissions() { ... }
    // @Override onRequestPermissionResult() { ... }

    
    private boolean stopPreview(CallbackContext callbackContext) {
        try {
            isPreviewActive = false;
            if (captureSession != null) {
                captureSession.stopRepeating();
            }
            callbackContext.success("Preview stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping preview", e);
            callbackContext.error("Failed to stop preview: " + e.getMessage());
        }
        return true;
    }

    private boolean takePhoto(CallbackContext callbackContext) {
        if (cameraDevice == null) {
            callbackContext.error("Camera not opened");
            return true;
        }

        cordova.getThreadPool().execute(() -> {
            try {
                captureStillPicture(callbackContext);
            } catch (Exception e) {
                Log.e(TAG, "Error taking photo", e);
                callbackContext.error("Failed to take photo: " + e.getMessage());
            }
        });
        
        return true;
    }

    private boolean closeCamera(CallbackContext callbackContext) {
        try {
            closeBackgroundThread();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            isPreviewActive = false;
            frameCallback = null;
            callbackContext.success("Camera closed");
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
            callbackContext.error("Failed to close camera: " + e.getMessage());
        }
        return true;
    }

    private void initializeCamera() throws CameraAccessException {
        cameraManager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
        
        String[] cameraIds = cameraManager.getCameraIdList();
        Log.d(TAG, "Found " + cameraIds.length + " cameras: " + Arrays.toString(cameraIds));
        
        // Se è specificato un cameraId, validalo prima di usarlo
        if (externalCameraId != null && !externalCameraId.isEmpty()) {
            // Verifica che l'ID esista
            boolean cameraExists = false;
            for (String id : cameraIds) {
                if (id.equals(externalCameraId)) {
                    cameraExists = true;
                    break;
                }
            }
            
            if (cameraExists) {
                Log.d(TAG, "Using specified camera ID: " + externalCameraId);
                startBackgroundThread();
                openCameraDevice();
                return;
            } else {
                Log.w(TAG, "Specified camera ID " + externalCameraId + " not found. Available IDs: " + Arrays.toString(cameraIds));
                throw new RuntimeException("Camera ID " + externalCameraId + " not found. Available cameras: " + Arrays.toString(cameraIds));
            }
        }
        
        // ← NUOVO: Cerca USB camere con il metodo avanzato
        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            if (isUsbExternalCamera(characteristics, cameraId)) {
                externalCameraId = cameraId;
                Log.d(TAG, "Found USB external camera: " + cameraId);
                break;
            }
        }
        
        if (externalCameraId == null) {
            throw new RuntimeException("No USB external camera found. Available cameras: " + Arrays.toString(cameraIds) + ". Use listCameras() to see detailed info and specify cameraId in options.");
        }

        Log.d(TAG, "Selected camera: " + externalCameraId);
        startBackgroundThread();
        openCameraDevice();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void closeBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error closing background thread", e);
            }
        }
    }

    private void openCameraDevice() throws CameraAccessException {
        if (ActivityCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        
        cameraManager.openCamera(externalCameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
                try {
                    createCameraPreviewSession();
                } catch (Exception e) {
                    Log.e(TAG, "Error creating preview session", e);
                    if (frameCallback != null) {
                        frameCallback.error("Failed to create preview session: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
                cameraDevice = null;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                camera.close();
                cameraDevice = null;
                if (frameCallback != null) {
                    frameCallback.error("Camera error: " + error);
                }
            }
        }, backgroundHandler);
    }

    private void createCameraPreviewSession() throws CameraAccessException {
        imageReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                if (isPreviewActive && frameCallback != null) {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        try {
                            String base64Frame = convertImageToBase64(image);
                            PluginResult result = new PluginResult(PluginResult.Status.OK, base64Frame);
                            result.setKeepCallback(true);
                            frameCallback.sendPluginResult(result);
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing frame", e);
                        } finally {
                            image.close();
                        }
                    }
                }
            }
        }, backgroundHandler);

        CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewRequestBuilder.addTarget(imageReader.getSurface());

        cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        
                        captureSession = session;
                        try {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            CaptureRequest previewRequest = previewRequestBuilder.build();
                            captureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                            isPreviewActive = true;
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Error starting preview", e);
                            if (frameCallback != null) {
                                frameCallback.error("Failed to start preview: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        if (frameCallback != null) {
                            frameCallback.error("Failed to configure camera session");
                        }
                    }
                }, null);
    }

    private void captureStillPicture(CallbackContext callbackContext) {
        try {
            if (cameraDevice == null) {
                callbackContext.error("Camera not available");
                return;
            }

            ImageReader stillReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.JPEG, 1);
            stillReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        try {
                            String filePath = saveImageToFile(image);
                            callbackContext.success(filePath);
                        } catch (Exception e) {
                            Log.e(TAG, "Error saving photo", e);
                            callbackContext.error("Failed to save photo: " + e.getMessage());
                        } finally {
                            image.close();
                            stillReader.close();
                        }
                    }
                }
            }, backgroundHandler);

            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(stillReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Arrays.asList(stillReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.capture(captureBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error capturing photo", e);
                                callbackContext.error("Failed to capture photo: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            callbackContext.error("Failed to configure capture session");
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Error in captureStillPicture", e);
            callbackContext.error("Failed to take photo: " + e.getMessage());
        }
    }

    private String convertImageToBase64(Image image) throws IOException {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
        byte[] jpegBytes = out.toByteArray();
        
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
    }

    private String saveImageToFile(Image image) throws IOException {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "USB_CAM_" + timeStamp + ".jpg";
        
        File storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "UsbCamera");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        
        File photoFile = new File(storageDir, fileName);
        
        try (FileOutputStream output = new FileOutputStream(photoFile)) {
            output.write(bytes);
        }
        
        return photoFile.getAbsolutePath();
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, permissions);
    }

    private String getFacingName(Integer lensFacing) {
        if (lensFacing == null) return "UNKNOWN";
        switch (lensFacing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK: return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "EXTERNAL";
            default: return "OTHER(" + lensFacing + ")";
        }
    }
    // ← NUOVO METODO: Identificazione affidabile USB camera
    private boolean isUsbExternalCamera(CameraCharacteristics characteristics, String cameraId) {
        try {
            // 1. Controlla se è esplicitamente EXTERNAL
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return true;
            }
            
            // 2. Controlla le capacità hardware tipiche delle USB camere
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                // USB camere spesso NON hanno certe capacità avanzate
                boolean hasAdvancedFeatures = false;
                for (int capability : capabilities) {
                    if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR ||
                        capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING ||
                        capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                        hasAdvancedFeatures = true;
                        break;
                    }
                }
                // Se NON ha funzioni avanzate, probabilmente è USB
                if (!hasAdvancedFeatures && capabilities.length <= 2) {
                    Log.d(TAG, "Camera " + cameraId + " identified as USB (limited capabilities)");
                    return true;
                }
            }
            
            // 3. Controlla i formati supportati
            StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap != null) {
                Size[] jpegSizes = configMap.getOutputSizes(ImageFormat.JPEG);
                Size[] yuvSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888);
                
                // USB camere spesso hanno limitazioni sui formati
                if (jpegSizes != null && yuvSizes != null) {
                    // Se ha pochi formati o risoluzioni limitate, probabilmente è USB
                    if (jpegSizes.length < 10 || yuvSizes.length < 5) {
                        Log.d(TAG, "Camera " + cameraId + " identified as USB (limited formats)");
                        return true;
                    }
                }
            }
            
            // 4. Controlla il supporto autofocus
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (afModes != null && afModes.length <= 1) {
                // USB camere spesso hanno autofocus limitato o assente
                Log.d(TAG, "Camera " + cameraId + " identified as USB (limited autofocus)");
                return true;
            }
            
            // 5. Controlla l'ID numerico alto (USB camere spesso hanno ID > 1)
            try {
                int numericId = Integer.parseInt(cameraId);
                if (numericId >= 2) {
                    Log.d(TAG, "Camera " + cameraId + " possibly USB (high ID number)");
                    return true;
                }
            } catch (NumberFormatException e) {
                // ID non numerico, potrebbe essere USB
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing camera " + cameraId, e);
            return false;
        }
    }

    // ← NUOVO METODO: Informazioni dettagliate per debug
    private JSONObject getDeviceInfo(CameraCharacteristics characteristics) {
        JSONObject info = new JSONObject();
        try {
            // Capacità
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            JSONArray capsArray = new JSONArray();
            if (capabilities != null) {
                for (int cap : capabilities) {
                    capsArray.put(cap);
                }
            }
            info.put("capabilities", capsArray);
            
            // Modalità autofocus
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            JSONArray afArray = new JSONArray();
            if (afModes != null) {
                for (int mode : afModes) {
                    afArray.put(mode);
                }
            }
            info.put("autofocusModes", afArray);
            
            // Formati supportati
            StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap != null) {
                Size[] jpegSizes = configMap.getOutputSizes(ImageFormat.JPEG);
                info.put("jpegSizesCount", jpegSizes != null ? jpegSizes.length : 0);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting device info", e);
        }
        return info;
    }

    // ← AGGIUNGI QUESTO METODO MANCANTE
    private boolean isSimpleUsbCamera(CameraCharacteristics characteristics, String cameraId) {
        try {
            // 1. Controlla se è esplicitamente EXTERNAL
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return true;
            }
            
            // 2. Se l'ID è >= 2, probabilmente è USB
            try {
                int numericId = Integer.parseInt(cameraId);
                return numericId >= 2;
            } catch (NumberFormatException e) {
                // ID non numerico, potrebbe essere USB
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking USB camera: " + cameraId, e);
            return false;
        }
    }

    // ← AGGIUNGI ANCHE QUESTO METODO
    private String getDeviceName(CameraCharacteristics characteristics, String cameraId) {
        try {
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            
            if (lensFacing != null) {
                switch (lensFacing) {
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        return "Front Camera (ID: " + cameraId + ")";
                    case CameraCharacteristics.LENS_FACING_BACK:
                        return "Back Camera (ID: " + cameraId + ")";
                    case CameraCharacteristics.LENS_FACING_EXTERNAL:
                        return "USB Camera (ID: " + cameraId + ")";
                    default:
                        return "Camera " + cameraId;
                }
            }
            
            return "Camera " + cameraId;
            
        } catch (Exception e) {
            return "Camera " + cameraId;
        }
    }
    
    // ← NUOVO METODO: Ottieni informazioni dettagliate USB
    private JSONObject getUsbDeviceInfo(String cameraId) {
        JSONObject usbInfo = new JSONObject();
        try {
            UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            
            // Cerca dispositivi video USB
            for (UsbDevice device : deviceList.values()) {
                // Controlla se è un dispositivo video (classe 14 = Video)
                if (device.getDeviceClass() == 14 || 
                    device.getDeviceClass() == 239 || // Miscellaneous Device
                    hasVideoInterface(device)) {
                    
                    String deviceName = device.getProductName();
                    String manufacturerName = device.getManufacturerName();
                    
                    // Se troviamo una corrispondenza con l'ID camera
                    if (deviceName != null && manufacturerName != null) {
                        usbInfo.put("productName", deviceName);
                        usbInfo.put("manufacturerName", manufacturerName);
                        usbInfo.put("vendorId", device.getVendorId());
                        usbInfo.put("productId", device.getProductId());
                        usbInfo.put("deviceName", device.getDeviceName());
                        usbInfo.put("fullName", manufacturerName + " " + deviceName);
                        
                        // Identifica specificamente Logitech
                        if (manufacturerName.toLowerCase().contains("logitech") || 
                            deviceName.toLowerCase().contains("logitech")) {
                            usbInfo.put("isLogitech", true);
                            usbInfo.put("displayName", "Logitech " + deviceName);
                        } else {
                            usbInfo.put("isLogitech", false);
                            usbInfo.put("displayName", manufacturerName + " " + deviceName);
                        }
                        
                        Log.d(TAG, "Found USB camera: " + manufacturerName + " " + deviceName);
                        break;
                    }
                }
            }
            
            // Se non troviamo info USB, usa fallback
            if (!usbInfo.has("productName")) {
                usbInfo.put("displayName", "USB Camera " + cameraId);
                usbInfo.put("isLogitech", false);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting USB device info", e);
            try {
                usbInfo.put("displayName", "USB Camera " + cameraId);
                usbInfo.put("isLogitech", false);
            } catch (JSONException je) {
                // Ignore
            }
        }
        return usbInfo;
    }

    // ← METODO HELPER: Controlla se ha interfaccia video
    private boolean hasVideoInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
            // Classe 14 = Video, Sottoclasse 1 = Video Control, 2 = Video Streaming
            if (usbInterface.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    // ← AGGIORNA IL METODO listCameras
    private boolean listCameras(CallbackContext callbackContext) {
        try {
            CameraManager manager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = manager.getCameraIdList();
            
            JSONArray cameras = new JSONArray();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                
                JSONObject camera = new JSONObject();
                camera.put("id", cameraId);
                camera.put("isUsbCamera", isSimpleUsbCamera(characteristics, cameraId));
                
                if (isSimpleUsbCamera(characteristics, cameraId)) {
                    // ← NUOVO: Ottieni info USB dettagliate
                    JSONObject usbInfo = getUsbDeviceInfo(cameraId);
                    camera.put("name", usbInfo.optString("displayName", "USB Camera " + cameraId));
                    camera.put("manufacturer", usbInfo.optString("manufacturerName", "Unknown"));
                    camera.put("product", usbInfo.optString("productName", "Unknown"));
                    camera.put("isLogitech", usbInfo.optBoolean("isLogitech", false));
                    camera.put("vendorId", usbInfo.optInt("vendorId", 0));
                    camera.put("productId", usbInfo.optInt("productId", 0));
                } else {
                    camera.put("name", getDeviceName(characteristics, cameraId));
                    camera.put("isLogitech", false);
                }
                
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                camera.put("lensFacing", lensFacing);
                camera.put("facingName", getFacingName(lensFacing));
                
                cameras.put(camera);
            }
            
            callbackContext.success(cameras);
        } catch (Exception e) {
            callbackContext.error("Error listing cameras: " + e.getMessage());
        }
        return true;
    }
}
