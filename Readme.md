# GNU Radio Android WLAN Receiver

This is a demo application for the GNU Radio on Android toolchain, implementing a WLAN receiver.
It is based on the [gr-ieee802-11](https://github.com/bastibl/gr-ieee802-11/) GNU Radio module.

![WLAN Receiver](doc/setup.png)

## Installation

Building the app requires the [GNU Radio Android toolchain](https://github.com/bastibl/gnuradio-android/). Please see this repository for further instructions.

## Running the App

Parameters like sample rate, center frequency, and gain are hard-coded for now. Please adapt `native-lib.cpp` accordingly.

