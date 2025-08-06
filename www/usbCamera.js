var exec = require('cordova/exec');

var UsbCamera = {
    /**
     * Open external USB camera
     * @param {Object} options - Camera options {width, height, fps}
     * @param {Function} onFrame - Callback for each frame (base64 string)
     * @param {Function} onError - Error callback
     */
    open: function(options, onFrame, onError) {
        options = options || {};
        
        var success = function(result) {
            if (typeof onFrame === 'function') {
                onFrame(result);
            }
        };
        
        var error = function(err) {
            if (typeof onError === 'function') {
                onError(err);
            }
        };
        
        exec(success, error, 'UsbExternalCamera', 'open', [options]);
    },
    
    /**
     * Stop camera preview
     * @param {Function} callback - Success callback
     * @param {Function} errorCallback - Error callback
     */
    stopPreview: function(callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'stopPreview', []);
    },
    
    /**
     * Take a photo and save to device storage
     * @param {Function} callback - Success callback with file path
     * @param {Function} errorCallback - Error callback
     */
    takePhoto: function(callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'takePhoto', []);
    },
    
    /**
     * Close camera and release resources
     * @param {Function} callback - Success callback
     * @param {Function} errorCallback - Error callback
     */
    close: function(callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'close', []);
    },
    listCameras: function (callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'listCameras', []);
    }
};

module.exports = UsbCamera;