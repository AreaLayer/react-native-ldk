package com.reactnativeldk
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.ldk.structs.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels

fun handleResolve(promise: Promise, res: LdkCallbackResponses) {
    if (res != LdkCallbackResponses.log_write_success) {
        LdkEventEmitter.send(EventTypes.native_log, "Success: ${res}")
    }
    promise.resolve(res.toString());
}

fun handleReject(promise: Promise, ldkError: LdkErrors, error: Error? = null) {
    if (error !== null) {
        LdkEventEmitter.send(EventTypes.native_log, "Error: ${ldkError}. Message: ${error.toString()}")
        promise.reject(ldkError.toString(), error);
    } else {
        LdkEventEmitter.send(EventTypes.native_log, "Error: ${ldkError}")
        promise.reject(ldkError.toString(), ldkError.toString())
    }
}

fun ByteArray.hexEncodedString(): String {
    return joinToString("") { "%02x".format(it) }
}

fun String.hexa(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun getProbabilisticScorer(path: String, networkGraph: NetworkGraph, logger: Logger): ProbabilisticScorer? {
    val params = ProbabilisticScoringParameters.with_default()

    val scorerFile = File(path + "/" + LdkFileNames.scorer.fileName)
    if (scorerFile.exists()) {
        val read = ProbabilisticScorer.read(scorerFile.readBytes(), params, networkGraph, logger)
        if (read.is_ok) {
            LdkEventEmitter.send(EventTypes.native_log, "Loaded scorer from disk")
            return (read as Result_ProbabilisticScorerDecodeErrorZ.Result_ProbabilisticScorerDecodeErrorZ_OK).res
        } else {
            LdkEventEmitter.send(EventTypes.native_log, "Failed to load cached scorer")
        }
    }

    val default_scorer = ProbabilisticScorer.of(params, networkGraph, logger)
    val score_res = ProbabilisticScorer.read(
        default_scorer.write(), params, networkGraph,
        logger
    )
    if (!score_res.is_ok) {
        return null
    }
    return (score_res as Result_ProbabilisticScorerDecodeErrorZ.Result_ProbabilisticScorerDecodeErrorZ_OK).res
}

val Invoice.asJson: WritableMap
    get() {
        val result = Arguments.createMap()
        val signedInv = into_signed_raw()
        val rawInvoice = signedInv.raw_invoice()

        if (amount_milli_satoshis() is Option_u64Z.Some) result.putInt("amount_satoshis", ((amount_milli_satoshis() as Option_u64Z.Some).some.toInt() / 1000)) else  result.putNull("amount_satoshis")
        result.putString("description", rawInvoice.description()?.into_inner())
        result.putBoolean("check_signature",  signedInv.check_signature())
        result.putBoolean("is_expired",  is_expired)
        result.putInt("duration_since_epoch",  duration_since_epoch().toInt())
        result.putInt("expiry_time",  expiry_time().toInt())
        result.putInt("min_final_cltv_expiry",  min_final_cltv_expiry().toInt())
        result.putHexString("payee_pub_key", rawInvoice.payee_pub_key()?._a)
        result.putHexString("recover_payee_pub_key", recover_payee_pub_key())
        result.putHexString("payment_hash", payment_hash())
        result.putHexString("payment_secret", payment_secret())
        result.putInt("timestamp", timestamp().toInt())
        result.putHexString("features", features()?.write())
        result.putInt("currency", currency().ordinal)
        result.putString("to_str", signedInv.to_str())

        val hints = Arguments.createArray()
        route_hints().iterator().forEach { routeHint ->
            val hops = Arguments.createArray()
            routeHint._a.iterator().forEach { hop ->
                hops.pushMap(hop.asJson)
            }
            hints.pushArray(hops)
        }
        result.putArray("route_hints", hints)

        return result
    }

val RouteHintHop.asJson: WritableMap
    get() {
        val result = Arguments.createMap()

        result.putHexString("src_node_id", _src_node_id)
        result.putString("short_channel_id", _short_channel_id.toString())

        return result
    }

//Our own channels
val ChannelDetails.asJson: WritableMap
    get() {
        val result = Arguments.createMap()

        result.putHexString("channel_id", _channel_id)
        result.putBoolean("is_public", _is_public)
        result.putBoolean("is_usable", _is_usable)
        result.putBoolean("is_channel_ready", _is_channel_ready)
        result.putBoolean("is_outbound", _is_outbound)
        result.putInt("balance_sat", _balance_msat.toInt() / 1000)
        result.putHexString("counterparty_node_id", _counterparty._node_id)
        result.putHexString("funding_txid", _funding_txo?._txid?.reversed()?.toByteArray())
        result.putHexString("channel_type", _channel_type?.write())
        result.putString("user_channel_id", _user_channel_id.leBytes.hexEncodedString())
        result.putInt("confirmations_required", (_confirmations_required as Option_u32Z.Some).some)
        (_short_channel_id as? Option_u64Z.Some)?.some?.toInt()
            ?.let { result.putString("short_channel_id", it.toString()) } //Optional number
        (_inbound_scid_alias as? Option_u64Z.Some)?.some?.toInt()
            ?.let { result.putInt("inbound_scid_alias", it) }
        (_inbound_scid_alias as? Option_u64Z.Some)?.some?.toInt()
            ?.let { result.putInt("inbound_payment_scid", it) }
        result.putInt("inbound_capacity_sat", _inbound_capacity_msat.toInt() / 1000)
        result.putInt("outbound_capacity_sat", _outbound_capacity_msat.toInt() / 1000)
        result.putInt("channel_value_satoshis", _channel_value_satoshis.toInt())
        (_force_close_spend_delay as? Option_u16Z.Some)?.some?.toInt()
            ?.let { result.putInt("force_close_spend_delay", it) }
        result.putInt("unspendable_punishment_reserve", (_unspendable_punishment_reserve as Option_u64Z.Some).some.toInt())

        return result
    }

//Channels in our network graph
val ChannelInfo.asJson: WritableMap
    get() {
        val result = Arguments.createMap()

        if (_capacity_sats is Option_u64Z.Some) result.putInt("capacity_sats", ((_capacity_sats as Option_u64Z.Some).some.toInt())) else result.putNull("capacity_sats")
        result.putHexString("node_one", _node_one.as_slice())
        result.putHexString("node_two", _node_two.as_slice())

        result.putInt("one_to_two_fees_base_sats", _one_to_two?._fees?._base_msat?.div(1000) ?: 0)
        result.putInt("one_to_two_fees_proportional_millionths", _one_to_two?._fees?._proportional_millionths ?: 0)
        result.putBoolean("one_to_two_enabled", _one_to_two?._enabled ?: false)
        result.putInt("one_to_two_last_update", _one_to_two?._last_update ?: 0)
        result.putInt("one_to_two_htlc_maximum_sats", (_one_to_two?._htlc_maximum_msat ?: 0).toInt() / 1000)
        result.putInt("one_to_two_htlc_minimum_sats", (_one_to_two?._htlc_minimum_msat ?: 0).toInt() / 1000)

        result.putInt("two_to_one_fees_base_sats", _two_to_one?._fees?._base_msat?.div(1000) ?: 0)
        result.putInt("two_to_one_fees_proportional_millionths", _two_to_one?._fees?._proportional_millionths ?: 0)
        result.putBoolean("two_to_one_enabled", _two_to_one?._enabled ?: false)
        result.putInt("two_to_one_last_update", _two_to_one?._last_update ?: 0)
        result.putInt("two_to_one_htlc_maximum_sats", (_two_to_one?._htlc_maximum_msat ?: 0).toInt() / 1000)
        result.putInt("two_to_one_htlc_minimum_sats", (_two_to_one?._htlc_minimum_msat ?: 0).toInt() / 1000)

        return result
    }

val NodeInfo.asJson: WritableMap
    get() {
        val result = Arguments.createMap()

        val shortChannelIds = Arguments.createArray()
        _channels.iterator().forEach { shortChannelIds.pushString(it.toString()) }
        result.putArray("shortChannelIds", shortChannelIds)
        result.putInt("lowest_inbound_channel_fees_base_sat", (_lowest_inbound_channel_fees?._base_msat ?: 0) / 1000)
        result.putInt("lowest_inbound_channel_fees_proportional_millionths", _lowest_inbound_channel_fees?._proportional_millionths ?: 0)
        result.putDouble("announcement_info_last_update", (_announcement_info?._last_update ?: 0).toDouble() * 1000)
        return result
    }

val RouteHop.asJson: WritableMap
    get() {
        val hop = Arguments.createMap()
        hop.putHexString("pubkey", _pubkey)
        hop.putInt("fee_sat", _fee_msat.toInt() / 1000)
        return hop
    }

fun WritableMap.putHexString(key: String, bytes: ByteArray?) {
    if (bytes != null) {
        putString(key, bytes.hexEncodedString())
    } else {
        putString(key, null)
    }
}

fun WritableArray.pushHexString(bytes: ByteArray) {
    pushString(bytes.hexEncodedString())
}

fun URL.downloadFile(destination: String, completion: (error: Error?) -> Unit) {
    try {
        openStream().use {
            Channels.newChannel(it).use { rbc ->
                FileOutputStream(destination).use { fos ->
                    fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
                    completion(null)
                }
            }
        }
    } catch (e: Exception) {
        completion(Error(e))
    }
}

fun RapidGossipSync.downloadAndUpdateGraph(downloadUrl: String, tempStoragePath: String, timestamp: Long, completion: (error: Error?) -> Unit) {
    val destinationFile = "$tempStoragePath$timestamp.bin"

    URL(downloadUrl + timestamp).downloadFile(destinationFile) {
        val res = update_network_graph(File(destinationFile).readBytes())
        if (!res.is_ok()) {
            val error = res as? Result_u32GraphSyncErrorZ.Result_u32GraphSyncErrorZ_Err

            (error?.err as? GraphSyncError.LightningError)?.let { lightningError ->
                return@downloadFile completion(Error("Rapid sync GraphSyncError.LightningError. " + lightningError.lightning_error._err))
            }

            (error?.err as? GraphSyncError.DecodeError)?.let { decodeError ->
                (decodeError.decode_error as? DecodeError.Io)?.let { decodeIOError ->
                    return@downloadFile completion(Error("Rapid sync GraphSyncError.DecodeError. " + decodeIOError.io.ordinal))
                }

                return@downloadFile completion(Error("Rapid sync GraphSyncError.DecodeError"))
            }

            return@downloadFile completion(Error("Unknown rapid sync error."))
        }

        val usedFile = File(destinationFile)
        if (usedFile.exists()) {
            usedFile.delete()
        }

        completion(null)
    }
}
