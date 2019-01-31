# enki

A simple kafka streams processor library

## Installation
# Installation
Using Leiningen / Clojars:

[![Clojars Project](https://img.shields.io/clojars/v/enki.svg)](https://clojars.org/enki)

## Usage
 
Include this library in your application and create a callback function with a single arity in the form of:

(defn fn [kv-pair-map] ...) 

Where the kv-pair-map has the form {:key key :record value} for whatever keys and values you are processing.

### Bugs


## License

Copyright © 2019 Christopher Lester

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
