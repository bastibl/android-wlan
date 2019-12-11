#include <jni.h>
#include <string>
#include <iostream>
#include <sstream>

#include <gnuradio/logger.h>
#include <gnuradio/top_block.h>
#include <gnuradio/blocks/ctrlport_probe2_f.h>
#include <gnuradio/uhd/usrp_source.h>
#include <gnuradio/blocks/sub.h>
#include <gnuradio/filter/fir_filter_blk.h>
#include <gnuradio/filter/single_pole_iir_filter_ff.h>
#include <gnuradio/analog/quadrature_demod_cf.h>
#include <gnuradio/blocks/ctrlport_probe2_f.h>
#include <gnuradio/blocks/message_debug.h>
#include <gnuradio/blocks/delay.h>
#include <gnuradio/blocks/complex_to_mag_squared.h>
#include <gnuradio/blocks/complex_to_mag.h>
#include <gnuradio/blocks/multiply_conjugate_cc.h>
#include <gnuradio/blocks/moving_average.h>
#include <gnuradio/blocks/divide.h>
#include <gnuradio/blocks/stream_to_vector.h>
#include <gnuradio/blocks/pdu_to_tagged_stream.h>
#include <gnuradio/blocks/null_sink.h>
#include <gnuradio/blocks/ctrlport_probe2_c.h>
#include <gnuradio/fft/fft_vcc.h>
#include <ieee802_11/sync_short.h>
#include <ieee802_11/sync_long.h>
#include <ieee802_11/frame_equalizer.h>
#include <ieee802_11/decode_mac.h>
#include <ieee802_11/parse_mac.h>
#include <gnuradio/zeromq/pub_msg_sink.h>
#include <stdlib.h>

gr::top_block_sptr tb;
gr::uhd::usrp_source::sptr src;

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_wlan_MainActivity_fgInit(JNIEnv * env, jobject /*this*/, int fd, jstring usbfsPath) {

    setenv("VOLK_CONFIGPATH", getenv("EXTERNAL_STORAGE"), 1);
    setenv("GR_CONF_CONTROLPORT_ON", "true", 1);

    const char *usbfs_path = env->GetStringUTFChars(usbfsPath, NULL);

    tb = gr::make_top_block("fg");

    std::stringstream args;
    args << "bbl=foo,type=b200,fd=" << fd << ",usbfs_path=" << usbfs_path;
    GR_INFO("fg", boost::str(boost::format("Using UHD args=%1%") % args.str()));

    ::uhd::stream_args_t stream_args;
    stream_args.cpu_format = "fc32";
    stream_args.otw_format = "sc16";

    int window_size = 48;
    int sync_length = 320;

    // Declare our GNU Radio blocks
    src = gr::uhd::usrp_source::make(args.str(), stream_args);
    src->set_samp_rate(20e6);
    src->set_center_freq(uhd::tune_request_t(5.18e9, 0));
    src->set_normalized_gain(0.7);

    gr::blocks::delay::sptr adelay = gr::blocks::delay::make(sizeof(gr_complex), 16);
    gr::blocks::multiply_conjugate_cc::sptr mconj = gr::blocks::multiply_conjugate_cc::make(1);
    gr::blocks::complex_to_mag_squared::sptr cm2 = gr::blocks::complex_to_mag_squared::make(1);
    gr::blocks::moving_average_ff::sptr maff = gr::blocks::moving_average_ff::make(window_size+16, 1, 4000, 1);
    gr::blocks::moving_average_cc::sptr macc = gr::blocks::moving_average_cc::make(window_size, 1, 4000, 1);
    gr::blocks::complex_to_mag::sptr cm = gr::blocks::complex_to_mag::make(1);
    gr::blocks::divide_ff::sptr divide = gr::blocks::divide_ff::make(1);
    gr::ieee802_11::sync_short::sptr sync_short = gr::ieee802_11::sync_short::make(0.56, 2, false, false);
    gr::ieee802_11::sync_long::sptr sync_long = gr::ieee802_11::sync_long::make(sync_length, false, false);
    gr::blocks::delay::sptr ldelay = gr::blocks::delay::make(sizeof(gr_complex), sync_length);
    gr::blocks::stream_to_vector::sptr s2v = gr::blocks::stream_to_vector::make(sizeof(gr_complex), 64);
    gr::fft::fft_vcc::sptr fft = gr::fft::fft_vcc::make(64, true, std::vector<float>(64, 1.0), true, 1);
    gr::ieee802_11::frame_equalizer::sptr equal = gr::ieee802_11::frame_equalizer::make(LS, 5.89e9, 5e6, false, false);
    gr::blocks::pdu_to_tagged_stream::sptr pdu2ts = gr::blocks::pdu_to_tagged_stream::make(gr::blocks::pdu::complex_t, "packet_len");
    gr::blocks::ctrlport_probe2_c::sptr probe = gr::blocks::ctrlport_probe2_c::make("const", "foo", 240, 0);
    gr::ieee802_11::decode_mac::sptr decode = gr::ieee802_11::decode_mac::make(false, false);
    gr::zeromq::pub_msg_sink::sptr zmq = gr::zeromq::pub_msg_sink::make("tcp://127.0.0.1:5503", -1);

    tb->connect(src, 0, adelay, 0);
    tb->connect(src, 0, cm2, 0);
    tb->connect(src, 0, mconj, 0);
    tb->connect(adelay, 0, mconj, 1);
    tb->connect(adelay, 0, sync_short, 0);
    tb->connect(mconj, 0, macc, 0);
    tb->connect(macc, 0, cm, 0);
    tb->connect(macc, 0, sync_short, 1);
    tb->connect(cm, 0, divide, 0);
    tb->connect(cm2, 0, maff, 0);
    tb->connect(maff, 0, divide, 1);
    tb->connect(divide, 0, sync_short, 2);
    tb->connect(sync_short, 0, sync_long, 0);
    tb->connect(sync_short, 0, ldelay, 0);
    tb->connect(ldelay, 0, sync_long, 1);
    tb->connect(sync_long, 0, s2v, 0);
    tb->connect(s2v, 0, fft, 0);
    tb->connect(fft, 0, equal, 0);
    tb->connect(equal, 0, decode, 0);
    tb->msg_connect(equal, "symbols", pdu2ts, "pdus");
    tb->connect(pdu2ts, 0, probe, 0);
    tb->msg_connect(decode, "out", zmq, "in");

    GR_DEBUG("gnuradio", "constructed flowgraph");

    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_wlan_MainActivity_fgStart(JNIEnv * env, jobject /*this*/, jstring tmpName) {

    nice(-200);
    const char *tmp_c;
    tmp_c = env->GetStringUTFChars(tmpName, NULL);
    setenv("TMP", tmp_c, 1);

    GR_DEBUG("gnuradio", "JNI starting flowgraph");
    tb->start();

    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_wlan_MainActivity_fgStop(JNIEnv * env, jobject /*this*/) {
    tb->stop();
    tb->wait();

    return nullptr;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_net_bastibl_wlan_MainActivity_fgRep(JNIEnv * env, jobject /*this*/) {

    return env->NewStringUTF(tb->edge_list().c_str());
}

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_wlan_MainActivity_setFreq(JNIEnv * env, jobject /*this*/, double freq) {

    if(src) {
        pmt::pmt_t command = pmt::cons(pmt::mp("freq"), pmt::mp(freq));
        src->post(pmt::intern("command"), command);
    }

    return nullptr;
}
