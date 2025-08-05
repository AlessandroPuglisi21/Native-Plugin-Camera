var UsbCamera = {
    open: function(options, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'open', [options]);
    },
    
    startPreview: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'startPreview', []);
    },
    
    stopPreview: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'stopPreview', []);
    },
    
    takePhoto: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'takePhoto', []);
    },
    
    close: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'close', []);
    },
    
    listCameras: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'listCameras', []);
    }
};

module.exports = UsbCamera;