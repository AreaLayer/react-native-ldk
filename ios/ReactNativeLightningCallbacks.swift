//
//  ReactNativeLightningCallbacks.swift
//  react-native-lightning
//
//  Created by Jason van den Berg on 2021/02/10.
//

import Foundation
import SwiftProtobuf

// Generic callback for LND function which will map responses back into the protobuf message type.
class LndCallback<T: SwiftProtobuf.Message>: NSObject, LndmobileCallbackProtocol, LndmobileRecvStreamProtocol {
    let completion: (T, Error?) -> Void

    init(_ completion: @escaping (T, Error?) -> Void) {
        let startedOnMainThread = Thread.current.isMainThread
        self.completion = { (response, error) in
            if startedOnMainThread {
              DispatchQueue.main.async { completion(response, error) }
            } else {
              completion(response, error)
            }
        }
    }

    func onResponse(_ p0: Data?) {
      guard let data = p0 else {
        return completion(T(), nil) //For calls like balance checks, an empty response should just be `T` defaults
      }

      do {
        completion(try T(serializedData: data), nil)
      } catch {
        completion(T(), LightningError.mapping)
      }
    }

    func onError(_ p0: Error?) {
      completion(T(), p0 ?? LightningError.unknown)
    }
}

// Callback for LND function when the request and response goes unchecked on the swift side. Used for sendCommand where the requests are constructed in javascript.
class BlindLndCallback: NSObject, LndmobileCallbackProtocol, LndmobileRecvStreamProtocol {
  let completion: (Data?, Error?) -> Void

  init(_ completion: @escaping (Data?, Error?) -> Void) {
      let startedOnMainThread = Thread.current.isMainThread
      self.completion = { (response, error) in
          if startedOnMainThread {
            DispatchQueue.main.async { completion(response, error) }
          } else {
            completion(response, error)
          }
      }
  }

  func onResponse(_ p0: Data?) {
    completion(p0, nil)
  }

  func onError(_ p0: Error?) {
    completion(nil, p0)
  }
}

// For LND callbacks that don't pass back any messages but can return errors
class LndEmptyResponseCallback: NSObject, LndmobileCallbackProtocol {
  let completion: (Error?) -> Void

  init(_ completion: @escaping (Error?) -> Void) {
      let startedOnMainThread = Thread.current.isMainThread
      self.completion = { error in
          if startedOnMainThread {
            DispatchQueue.main.async { completion(error) }
          } else {
            completion(error)
          }
      }
  }

  func onResponse(_ p0: Data?) {
    completion(nil)
  }

  func onError(_ p0: Error?) {
    completion(p0 ?? LightningError.unknown)
  }
}