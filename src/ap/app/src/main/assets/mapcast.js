
/* ===== java-bridge ===== */
/* Generated from frida-java-bridge 7.0.13 for EVCC Frida 17. License: LGPL-2.0 WITH WxWindows-exception-3.1. */
(()=>{var _i=Object.defineProperty;var mi=(t,e)=>{for(var n in e)_i(t,n,{get:e[n],enumerable:!0})};var we=[],be=[],Jt="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";for(let t=0,e=Jt.length;t<e;++t)we[t]=Jt[t],be[Jt.charCodeAt(t)]=t;be[45]=62;be[95]=63;function gi(t){let e=t.length;if(e%4>0)throw new Error("Invalid string. Length must be a multiple of 4");let n=t.indexOf("=");n===-1&&(n=e);let r=n===e?0:4-n%4;return[n,r]}function bi(t,e,n){return(e+n)*3/4-n}function Ir(t){let e=gi(t),n=e[0],r=e[1],o=new Uint8Array(bi(t,n,r)),i=0,s=r>0?n-4:n,c;for(c=0;c<s;c+=4){let a=be[t.charCodeAt(c)]<<18|be[t.charCodeAt(c+1)]<<12|be[t.charCodeAt(c+2)]<<6|be[t.charCodeAt(c+3)];o[i++]=a>>16&255,o[i++]=a>>8&255,o[i++]=a&255}if(r===2){let a=be[t.charCodeAt(c)]<<2|be[t.charCodeAt(c+1)]>>4;o[i++]=a&255}if(r===1){let a=be[t.charCodeAt(c)]<<10|be[t.charCodeAt(c+1)]<<4|be[t.charCodeAt(c+2)]>>2;o[i++]=a>>8&255,o[i++]=a&255}return o}function yi(t){return we[t>>18&63]+we[t>>12&63]+we[t>>6&63]+we[t&63]}function Ei(t,e,n){let r=[];for(let o=e;o<n;o+=3){let i=(t[o]<<16&16711680)+(t[o+1]<<8&65280)+(t[o+2]&255);r.push(yi(i))}return r.join("")}function Gt(t){let e=t.length,n=e%3,r=[],o=16383;for(let i=0,s=e-n;i<s;i+=o)r.push(Ei(t,i,i+o>s?s:i+o));if(n===1){let i=t[e-1];r.push(we[i>>2]+we[i<<4&63]+"==")}else if(n===2){let i=(t[e-2]<<8)+t[e-1];r.push(we[i>>10]+we[i>>4&63]+we[i<<2&63]+"=")}return r.join("")}function et(t,e,n,r,o){let i,s,c=o*8-r-1,a=(1<<c)-1,l=a>>1,d=-7,p=n?o-1:0,f=n?-1:1,u=t[e+p];for(p+=f,i=u&(1<<-d)-1,u>>=-d,d+=c;d>0;)i=i*256+t[e+p],p+=f,d-=8;for(s=i&(1<<-d)-1,i>>=-d,d+=r;d>0;)s=s*256+t[e+p],p+=f,d-=8;if(i===0)i=1-l;else{if(i===a)return s?NaN:(u?-1:1)*(1/0);s=s+Math.pow(2,r),i=i-l}return(u?-1:1)*s*Math.pow(2,i-r)}function $t(t,e,n,r,o,i){let s,c,a,l=i*8-o-1,d=(1<<l)-1,p=d>>1,f=o===23?Math.pow(2,-24)-Math.pow(2,-77):0,u=r?0:i-1,_=r?1:-1,h=e<0||e===0&&1/e<0?1:0;for(e=Math.abs(e),isNaN(e)||e===1/0?(c=isNaN(e)?1:0,s=d):(s=Math.floor(Math.log(e)/Math.LN2),e*(a=Math.pow(2,-s))<1&&(s--,a*=2),s+p>=1?e+=f/a:e+=f*Math.pow(2,1-p),e*a>=2&&(s++,a/=2),s+p>=d?(c=0,s=d):s+p>=1?(c=(e*a-1)*Math.pow(2,o),s=s+p):(c=e*Math.pow(2,p-1)*Math.pow(2,o),s=0));o>=8;)t[n+u]=c&255,u+=_,c/=256,o-=8;for(s=s<<o|c,l+=o;l>0;)t[n+u]=s&255,u+=_,s/=256,l-=8;t[n+u-_]|=h*128}var wi={INSPECT_MAX_BYTES:50},Ht=2147483647;m.TYPED_ARRAY_SUPPORT=!0;Object.defineProperty(m.prototype,"parent",{enumerable:!0,get:function(){if(m.isBuffer(this))return this.buffer}});Object.defineProperty(m.prototype,"offset",{enumerable:!0,get:function(){if(m.isBuffer(this))return this.byteOffset}});function Ae(t){if(t>Ht)throw new RangeError('The value "'+t+'" is invalid for option "size"');let e=new Uint8Array(t);return Object.setPrototypeOf(e,m.prototype),e}function m(t,e,n){if(typeof t=="number"){if(typeof e=="string")throw new TypeError('The "string" argument must be of type string. Received type number');return Kt(t)}return Lr(t,e,n)}m.poolSize=8192;function Lr(t,e,n){if(typeof t=="string")return Ci(t,e);if(ArrayBuffer.isView(t))return Ti(t);if(t==null)throw new TypeError("The first argument must be one of type string, Buffer, ArrayBuffer, Array, or Array-like Object. Received type "+typeof t);if(t instanceof ArrayBuffer||t&&t.buffer instanceof ArrayBuffer||t instanceof SharedArrayBuffer||t&&t.buffer instanceof SharedArrayBuffer)return qt(t,e,n);if(typeof t=="number")throw new TypeError('The "value" argument must not be of type number. Received type number');let r=t.valueOf&&t.valueOf();if(r!=null&&r!==t)return m.from(r,e,n);let o=Ai(t);if(o)return o;if(typeof Symbol<"u"&&Symbol.toPrimitive!=null&&typeof t[Symbol.toPrimitive]=="function")return m.from(t[Symbol.toPrimitive]("string"),e,n);throw new TypeError("The first argument must be one of type string, Buffer, ArrayBuffer, Array, or Array-like Object. Received type "+typeof t)}m.from=function(t,e,n){return Lr(t,e,n)};Object.setPrototypeOf(m.prototype,Uint8Array.prototype);Object.setPrototypeOf(m,Uint8Array);function xr(t){if(typeof t!="number")throw new TypeError('"size" argument must be of type number');if(t<0)throw new RangeError('The value "'+t+'" is invalid for option "size"')}function Ii(t,e,n){return xr(t),t<=0?Ae(t):e!==void 0?typeof n=="string"?Ae(t).fill(e,n):Ae(t).fill(e):Ae(t)}m.alloc=function(t,e,n){return Ii(t,e,n)};function Kt(t){return xr(t),Ae(t<0?0:Qt(t)|0)}m.allocUnsafe=function(t){return Kt(t)};m.allocUnsafeSlow=function(t){return Kt(t)};function Ci(t,e){if((typeof e!="string"||e==="")&&(e="utf8"),!m.isEncoding(e))throw new TypeError("Unknown encoding: "+e);let n=Nr(t,e)|0,r=Ae(n),o=r.write(t,e);return o!==n&&(r=r.slice(0,o)),r}function Zt(t){let e=t.length<0?0:Qt(t.length)|0,n=Ae(e);for(let r=0;r<e;r+=1)n[r]=t[r]&255;return n}function Ti(t){if(t instanceof Uint8Array){let e=new Uint8Array(t);return qt(e.buffer,e.byteOffset,e.byteLength)}return Zt(t)}function qt(t,e,n){if(e<0||t.byteLength<e)throw new RangeError('"offset" is outside of buffer bounds');if(t.byteLength<e+(n||0))throw new RangeError('"length" is outside of buffer bounds');let r;return e===void 0&&n===void 0?r=new Uint8Array(t):n===void 0?r=new Uint8Array(t,e):r=new Uint8Array(t,e,n),Object.setPrototypeOf(r,m.prototype),r}function Ai(t){if(m.isBuffer(t)){let e=Qt(t.length)|0,n=Ae(e);return n.length===0||t.copy(n,0,0,e),n}if(t.length!==void 0)return typeof t.length!="number"||Number.isNaN(t.length)?Ae(0):Zt(t);if(t.type==="Buffer"&&Array.isArray(t.data))return Zt(t.data)}function Qt(t){if(t>=Ht)throw new RangeError("Attempt to allocate Buffer larger than maximum size: 0x"+Ht.toString(16)+" bytes");return t|0}m.isBuffer=function(e){return e!=null&&e._isBuffer===!0&&e!==m.prototype};m.compare=function(e,n){if(e instanceof Uint8Array&&(e=m.from(e,e.offset,e.byteLength)),n instanceof Uint8Array&&(n=m.from(n,n.offset,n.byteLength)),!m.isBuffer(e)||!m.isBuffer(n))throw new TypeError('The "buf1", "buf2" arguments must be one of type Buffer or Uint8Array');if(e===n)return 0;let r=e.length,o=n.length;for(let i=0,s=Math.min(r,o);i<s;++i)if(e[i]!==n[i]){r=e[i],o=n[i];break}return r<o?-1:o<r?1:0};m.isEncoding=function(e){switch(String(e).toLowerCase()){case"hex":case"utf8":case"utf-8":case"ascii":case"latin1":case"binary":case"base64":case"ucs2":case"ucs-2":case"utf16le":case"utf-16le":return!0;default:return!1}};m.concat=function(e,n){if(!Array.isArray(e))throw new TypeError('"list" argument must be an Array of Buffers');if(e.length===0)return m.alloc(0);let r;if(n===void 0)for(n=0,r=0;r<e.length;++r)n+=e[r].length;let o=m.allocUnsafe(n),i=0;for(r=0;r<e.length;++r){let s=e[r];if(s instanceof Uint8Array)i+s.length>o.length?(m.isBuffer(s)||(s=m.from(s.buffer,s.byteOffset,s.byteLength)),s.copy(o,i)):Uint8Array.prototype.set.call(o,s,i);else if(m.isBuffer(s))s.copy(o,i);else throw new TypeError('"list" argument must be an Array of Buffers');i+=s.length}return o};function Nr(t,e){if(m.isBuffer(t))return t.length;if(ArrayBuffer.isView(t)||t instanceof ArrayBuffer)return t.byteLength;if(typeof t!="string")throw new TypeError('The "string" argument must be one of type string, Buffer, or ArrayBuffer. Received type '+typeof t);let n=t.length,r=arguments.length>2&&arguments[2]===!0;if(!r&&n===0)return 0;let o=!1;for(;;)switch(e){case"ascii":case"latin1":case"binary":return n;case"utf8":case"utf-8":return Wt(t).length;case"ucs2":case"ucs-2":case"utf16le":case"utf-16le":return n*2;case"hex":return n>>>1;case"base64":return Ur(t).length;default:if(o)return r?-1:Wt(t).length;e=(""+e).toLowerCase(),o=!0}}m.byteLength=Nr;function Li(t,e,n){let r=!1;if((e===void 0||e<0)&&(e=0),e>this.length||((n===void 0||n>this.length)&&(n=this.length),n<=0)||(n>>>=0,e>>>=0,n<=e))return"";for(t||(t="utf8");;)switch(t){case"hex":return Di(this,e,n);case"utf8":case"utf-8":return Mr(this,e,n);case"ascii":return Pi(this,e,n);case"latin1":case"binary":return Fi(this,e,n);case"base64":return Oi(this,e,n);case"ucs2":case"ucs-2":case"utf16le":case"utf-16le":return Ui(this,e,n);default:if(r)throw new TypeError("Unknown encoding: "+t);t=(t+"").toLowerCase(),r=!0}}m.prototype._isBuffer=!0;function Re(t,e,n){let r=t[e];t[e]=t[n],t[n]=r}m.prototype.swap16=function(){let e=this.length;if(e%2!==0)throw new RangeError("Buffer size must be a multiple of 16-bits");for(let n=0;n<e;n+=2)Re(this,n,n+1);return this};m.prototype.swap32=function(){let e=this.length;if(e%4!==0)throw new RangeError("Buffer size must be a multiple of 32-bits");for(let n=0;n<e;n+=4)Re(this,n,n+3),Re(this,n+1,n+2);return this};m.prototype.swap64=function(){let e=this.length;if(e%8!==0)throw new RangeError("Buffer size must be a multiple of 64-bits");for(let n=0;n<e;n+=8)Re(this,n,n+7),Re(this,n+1,n+6),Re(this,n+2,n+5),Re(this,n+3,n+4);return this};m.prototype.toString=function(){let e=this.length;return e===0?"":arguments.length===0?Mr(this,0,e):Li.apply(this,arguments)};m.prototype.toLocaleString=m.prototype.toString;m.prototype.equals=function(e){if(!m.isBuffer(e))throw new TypeError("Argument must be a Buffer");return this===e?!0:m.compare(this,e)===0};m.prototype.inspect=function(){let e="",n=wi.INSPECT_MAX_BYTES;return e=this.toString("hex",0,n).replace(/(.{2})/g,"$1 ").trim(),this.length>n&&(e+=" ... "),"<Buffer "+e+">"};m.prototype[Symbol.for("nodejs.util.inspect.custom")]=m.prototype.inspect;m.prototype.compare=function(e,n,r,o,i){if(e instanceof Uint8Array&&(e=m.from(e,e.offset,e.byteLength)),!m.isBuffer(e))throw new TypeError('The "target" argument must be one of type Buffer or Uint8Array. Received type '+typeof e);if(n===void 0&&(n=0),r===void 0&&(r=e?e.length:0),o===void 0&&(o=0),i===void 0&&(i=this.length),n<0||r>e.length||o<0||i>this.length)throw new RangeError("out of range index");if(o>=i&&n>=r)return 0;if(o>=i)return-1;if(n>=r)return 1;if(n>>>=0,r>>>=0,o>>>=0,i>>>=0,this===e)return 0;let s=i-o,c=r-n,a=Math.min(s,c),l=this.slice(o,i),d=e.slice(n,r);for(let p=0;p<a;++p)if(l[p]!==d[p]){s=l[p],c=d[p];break}return s<c?-1:c<s?1:0};function kr(t,e,n,r,o){if(t.length===0)return-1;if(typeof n=="string"?(r=n,n=0):n>2147483647?n=2147483647:n<-2147483648&&(n=-2147483648),n=+n,Number.isNaN(n)&&(n=o?0:t.length-1),n<0&&(n=t.length+n),n>=t.length){if(o)return-1;n=t.length-1}else if(n<0)if(o)n=0;else return-1;if(typeof e=="string"&&(e=m.from(e,r)),m.isBuffer(e))return e.length===0?-1:Cr(t,e,n,r,o);if(typeof e=="number")return e=e&255,typeof Uint8Array.prototype.indexOf=="function"?o?Uint8Array.prototype.indexOf.call(t,e,n):Uint8Array.prototype.lastIndexOf.call(t,e,n):Cr(t,[e],n,r,o);throw new TypeError("val must be string, number or Buffer")}function Cr(t,e,n,r,o){let i=1,s=t.length,c=e.length;if(r!==void 0&&(r=String(r).toLowerCase(),r==="ucs2"||r==="ucs-2"||r==="utf16le"||r==="utf-16le")){if(t.length<2||e.length<2)return-1;i=2,s/=2,c/=2,n/=2}function a(d,p){return i===1?d[p]:d.readUInt16BE(p*i)}let l;if(o){let d=-1;for(l=n;l<s;l++)if(a(t,l)===a(e,d===-1?0:l-d)){if(d===-1&&(d=l),l-d+1===c)return d*i}else d!==-1&&(l-=l-d),d=-1}else for(n+c>s&&(n=s-c),l=n;l>=0;l--){let d=!0;for(let p=0;p<c;p++)if(a(t,l+p)!==a(e,p)){d=!1;break}if(d)return l}return-1}m.prototype.includes=function(e,n,r){return this.indexOf(e,n,r)!==-1};m.prototype.indexOf=function(e,n,r){return kr(this,e,n,r,!0)};m.prototype.lastIndexOf=function(e,n,r){return kr(this,e,n,r,!1)};function xi(t,e,n,r){n=Number(n)||0;let o=t.length-n;r?(r=Number(r),r>o&&(r=o)):r=o;let i=e.length;r>i/2&&(r=i/2);let s;for(s=0;s<r;++s){let c=parseInt(e.substr(s*2,2),16);if(Number.isNaN(c))return s;t[n+s]=c}return s}function Ni(t,e,n,r){return bt(Wt(e,t.length-n),t,n,r)}function ki(t,e,n,r){return bt(Ji(e),t,n,r)}function Mi(t,e,n,r){return bt(Ur(e),t,n,r)}function ji(t,e,n,r){return bt(Gi(e,t.length-n),t,n,r)}m.prototype.write=function(e,n,r,o){if(n===void 0)o="utf8",r=this.length,n=0;else if(r===void 0&&typeof n=="string")o=n,r=this.length,n=0;else if(isFinite(n))n=n>>>0,isFinite(r)?(r=r>>>0,o===void 0&&(o="utf8")):(o=r,r=void 0);else throw new Error("Buffer.write(string, encoding, offset[, length]) is no longer supported");let i=this.length-n;if((r===void 0||r>i)&&(r=i),e.length>0&&(r<0||n<0)||n>this.length)throw new RangeError("Attempt to write outside buffer bounds");o||(o="utf8");let s=!1;for(;;)switch(o){case"hex":return xi(this,e,n,r);case"utf8":case"utf-8":return Ni(this,e,n,r);case"ascii":case"latin1":case"binary":return ki(this,e,n,r);case"base64":return Mi(this,e,n,r);case"ucs2":case"ucs-2":case"utf16le":case"utf-16le":return ji(this,e,n,r);default:if(s)throw new TypeError("Unknown encoding: "+o);o=(""+o).toLowerCase(),s=!0}};m.prototype.toJSON=function(){return{type:"Buffer",data:Array.prototype.slice.call(this._arr||this,0)}};function Oi(t,e,n){return e===0&&n===t.length?Gt(t):Gt(t.slice(e,n))}function Mr(t,e,n){n=Math.min(t.length,n);let r=[],o=e;for(;o<n;){let i=t[o],s=null,c=i>239?4:i>223?3:i>191?2:1;if(o+c<=n){let a,l,d,p;switch(c){case 1:i<128&&(s=i);break;case 2:a=t[o+1],(a&192)===128&&(p=(i&31)<<6|a&63,p>127&&(s=p));break;case 3:a=t[o+1],l=t[o+2],(a&192)===128&&(l&192)===128&&(p=(i&15)<<12|(a&63)<<6|l&63,p>2047&&(p<55296||p>57343)&&(s=p));break;case 4:a=t[o+1],l=t[o+2],d=t[o+3],(a&192)===128&&(l&192)===128&&(d&192)===128&&(p=(i&15)<<18|(a&63)<<12|(l&63)<<6|d&63,p>65535&&p<1114112&&(s=p))}}s===null?(s=65533,c=1):s>65535&&(s-=65536,r.push(s>>>10&1023|55296),s=56320|s&1023),r.push(s),o+=c}return Ri(r)}var Tr=4096;function Ri(t){let e=t.length;if(e<=Tr)return String.fromCharCode.apply(String,t);let n="",r=0;for(;r<e;)n+=String.fromCharCode.apply(String,t.slice(r,r+=Tr));return n}function Pi(t,e,n){let r="";n=Math.min(t.length,n);for(let o=e;o<n;++o)r+=String.fromCharCode(t[o]&127);return r}function Fi(t,e,n){let r="";n=Math.min(t.length,n);for(let o=e;o<n;++o)r+=String.fromCharCode(t[o]);return r}function Di(t,e,n){let r=t.length;(!e||e<0)&&(e=0),(!n||n<0||n>r)&&(n=r);let o="";for(let i=e;i<n;++i)o+=$i[t[i]];return o}function Ui(t,e,n){let r=t.slice(e,n),o="";for(let i=0;i<r.length-1;i+=2)o+=String.fromCharCode(r[i]+r[i+1]*256);return o}m.prototype.slice=function(e,n){let r=this.length;e=~~e,n=n===void 0?r:~~n,e<0?(e+=r,e<0&&(e=0)):e>r&&(e=r),n<0?(n+=r,n<0&&(n=0)):n>r&&(n=r),n<e&&(n=e);let o=this.subarray(e,n);return Object.setPrototypeOf(o,m.prototype),o};function se(t,e,n){if(t%1!==0||t<0)throw new RangeError("offset is not uint");if(t+e>n)throw new RangeError("Trying to access beyond buffer length")}m.prototype.readUintLE=m.prototype.readUIntLE=function(e,n,r){e=e>>>0,n=n>>>0,r||se(e,n,this.length);let o=this[e],i=1,s=0;for(;++s<n&&(i*=256);)o+=this[e+s]*i;return o};m.prototype.readUintBE=m.prototype.readUIntBE=function(e,n,r){e=e>>>0,n=n>>>0,r||se(e,n,this.length);let o=this[e+--n],i=1;for(;n>0&&(i*=256);)o+=this[e+--n]*i;return o};m.prototype.readUint8=m.prototype.readUInt8=function(e,n){return e=e>>>0,n||se(e,1,this.length),this[e]};m.prototype.readUint16LE=m.prototype.readUInt16LE=function(e,n){return e=e>>>0,n||se(e,2,this.length),this[e]|this[e+1]<<8};m.prototype.readUint16BE=m.prototype.readUInt16BE=function(e,n){return e=e>>>0,n||se(e,2,this.length),this[e]<<8|this[e+1]};m.prototype.readUint32LE=m.prototype.readUInt32LE=function(e,n){return e=e>>>0,n||se(e,4,this.length),(this[e]|this[e+1]<<8|this[e+2]<<16)+this[e+3]*16777216};m.prototype.readUint32BE=m.prototype.readUInt32BE=function(e,n){return e=e>>>0,n||se(e,4,this.length),this[e]*16777216+(this[e+1]<<16|this[e+2]<<8|this[e+3])};m.prototype.readBigUInt64LE=function(e){e=e>>>0,Ge(e,"offset");let n=this[e],r=this[e+7];(n===void 0||r===void 0)&&tt(e,this.length-8);let o=n+this[++e]*2**8+this[++e]*2**16+this[++e]*2**24,i=this[++e]+this[++e]*2**8+this[++e]*2**16+r*2**24;return BigInt(o)+(BigInt(i)<<BigInt(32))};m.prototype.readBigUInt64BE=function(e){e=e>>>0,Ge(e,"offset");let n=this[e],r=this[e+7];(n===void 0||r===void 0)&&tt(e,this.length-8);let o=n*2**24+this[++e]*2**16+this[++e]*2**8+this[++e],i=this[++e]*2**24+this[++e]*2**16+this[++e]*2**8+r;return(BigInt(o)<<BigInt(32))+BigInt(i)};m.prototype.readIntLE=function(e,n,r){e=e>>>0,n=n>>>0,r||se(e,n,this.length);let o=this[e],i=1,s=0;for(;++s<n&&(i*=256);)o+=this[e+s]*i;return i*=128,o>=i&&(o-=Math.pow(2,8*n)),o};m.prototype.readIntBE=function(e,n,r){e=e>>>0,n=n>>>0,r||se(e,n,this.length);let o=n,i=1,s=this[e+--o];for(;o>0&&(i*=256);)s+=this[e+--o]*i;return i*=128,s>=i&&(s-=Math.pow(2,8*n)),s};m.prototype.readInt8=function(e,n){return e=e>>>0,n||se(e,1,this.length),this[e]&128?(255-this[e]+1)*-1:this[e]};m.prototype.readInt16LE=function(e,n){e=e>>>0,n||se(e,2,this.length);let r=this[e]|this[e+1]<<8;return r&32768?r|4294901760:r};m.prototype.readInt16BE=function(e,n){e=e>>>0,n||se(e,2,this.length);let r=this[e+1]|this[e]<<8;return r&32768?r|4294901760:r};m.prototype.readInt32LE=function(e,n){return e=e>>>0,n||se(e,4,this.length),this[e]|this[e+1]<<8|this[e+2]<<16|this[e+3]<<24};m.prototype.readInt32BE=function(e,n){return e=e>>>0,n||se(e,4,this.length),this[e]<<24|this[e+1]<<16|this[e+2]<<8|this[e+3]};m.prototype.readBigInt64LE=function(e){e=e>>>0,Ge(e,"offset");let n=this[e],r=this[e+7];(n===void 0||r===void 0)&&tt(e,this.length-8);let o=this[e+4]+this[e+5]*2**8+this[e+6]*2**16+(r<<24);return(BigInt(o)<<BigInt(32))+BigInt(n+this[++e]*2**8+this[++e]*2**16+this[++e]*2**24)};m.prototype.readBigInt64BE=function(e){e=e>>>0,Ge(e,"offset");let n=this[e],r=this[e+7];(n===void 0||r===void 0)&&tt(e,this.length-8);let o=(n<<24)+this[++e]*2**16+this[++e]*2**8+this[++e];return(BigInt(o)<<BigInt(32))+BigInt(this[++e]*2**24+this[++e]*2**16+this[++e]*2**8+r)};m.prototype.readFloatLE=function(e,n){return e=e>>>0,n||se(e,4,this.length),et(this,e,!0,23,4)};m.prototype.readFloatBE=function(e,n){return e=e>>>0,n||se(e,4,this.length),et(this,e,!1,23,4)};m.prototype.readDoubleLE=function(e,n){return e=e>>>0,n||se(e,8,this.length),et(this,e,!0,52,8)};m.prototype.readDoubleBE=function(e,n){return e=e>>>0,n||se(e,8,this.length),et(this,e,!1,52,8)};function pe(t,e,n,r,o,i){if(!m.isBuffer(t))throw new TypeError('"buffer" argument must be a Buffer instance');if(e>o||e<i)throw new RangeError('"value" argument is out of bounds');if(n+r>t.length)throw new RangeError("Index out of range")}m.prototype.writeUintLE=m.prototype.writeUIntLE=function(e,n,r,o){if(e=+e,n=n>>>0,r=r>>>0,!o){let c=Math.pow(2,8*r)-1;pe(this,e,n,r,c,0)}let i=1,s=0;for(this[n]=e&255;++s<r&&(i*=256);)this[n+s]=e/i&255;return n+r};m.prototype.writeUintBE=m.prototype.writeUIntBE=function(e,n,r,o){if(e=+e,n=n>>>0,r=r>>>0,!o){let c=Math.pow(2,8*r)-1;pe(this,e,n,r,c,0)}let i=r-1,s=1;for(this[n+i]=e&255;--i>=0&&(s*=256);)this[n+i]=e/s&255;return n+r};m.prototype.writeUint8=m.prototype.writeUInt8=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,1,255,0),this[n]=e&255,n+1};m.prototype.writeUint16LE=m.prototype.writeUInt16LE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,2,65535,0),this[n]=e&255,this[n+1]=e>>>8,n+2};m.prototype.writeUint16BE=m.prototype.writeUInt16BE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,2,65535,0),this[n]=e>>>8,this[n+1]=e&255,n+2};m.prototype.writeUint32LE=m.prototype.writeUInt32LE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,4,4294967295,0),this[n+3]=e>>>24,this[n+2]=e>>>16,this[n+1]=e>>>8,this[n]=e&255,n+4};m.prototype.writeUint32BE=m.prototype.writeUInt32BE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,4,4294967295,0),this[n]=e>>>24,this[n+1]=e>>>16,this[n+2]=e>>>8,this[n+3]=e&255,n+4};function jr(t,e,n,r,o){Dr(e,r,o,t,n,7);let i=Number(e&BigInt(4294967295));t[n++]=i,i=i>>8,t[n++]=i,i=i>>8,t[n++]=i,i=i>>8,t[n++]=i;let s=Number(e>>BigInt(32)&BigInt(4294967295));return t[n++]=s,s=s>>8,t[n++]=s,s=s>>8,t[n++]=s,s=s>>8,t[n++]=s,n}function Or(t,e,n,r,o){Dr(e,r,o,t,n,7);let i=Number(e&BigInt(4294967295));t[n+7]=i,i=i>>8,t[n+6]=i,i=i>>8,t[n+5]=i,i=i>>8,t[n+4]=i;let s=Number(e>>BigInt(32)&BigInt(4294967295));return t[n+3]=s,s=s>>8,t[n+2]=s,s=s>>8,t[n+1]=s,s=s>>8,t[n]=s,n+8}m.prototype.writeBigUInt64LE=function(e,n=0){return jr(this,e,n,BigInt(0),BigInt("0xffffffffffffffff"))};m.prototype.writeBigUInt64BE=function(e,n=0){return Or(this,e,n,BigInt(0),BigInt("0xffffffffffffffff"))};m.prototype.writeIntLE=function(e,n,r,o){if(e=+e,n=n>>>0,!o){let a=Math.pow(2,8*r-1);pe(this,e,n,r,a-1,-a)}let i=0,s=1,c=0;for(this[n]=e&255;++i<r&&(s*=256);)e<0&&c===0&&this[n+i-1]!==0&&(c=1),this[n+i]=(e/s>>0)-c&255;return n+r};m.prototype.writeIntBE=function(e,n,r,o){if(e=+e,n=n>>>0,!o){let a=Math.pow(2,8*r-1);pe(this,e,n,r,a-1,-a)}let i=r-1,s=1,c=0;for(this[n+i]=e&255;--i>=0&&(s*=256);)e<0&&c===0&&this[n+i+1]!==0&&(c=1),this[n+i]=(e/s>>0)-c&255;return n+r};m.prototype.writeInt8=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,1,127,-128),e<0&&(e=255+e+1),this[n]=e&255,n+1};m.prototype.writeInt16LE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,2,32767,-32768),this[n]=e&255,this[n+1]=e>>>8,n+2};m.prototype.writeInt16BE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,2,32767,-32768),this[n]=e>>>8,this[n+1]=e&255,n+2};m.prototype.writeInt32LE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,4,2147483647,-2147483648),this[n]=e&255,this[n+1]=e>>>8,this[n+2]=e>>>16,this[n+3]=e>>>24,n+4};m.prototype.writeInt32BE=function(e,n,r){return e=+e,n=n>>>0,r||pe(this,e,n,4,2147483647,-2147483648),e<0&&(e=4294967295+e+1),this[n]=e>>>24,this[n+1]=e>>>16,this[n+2]=e>>>8,this[n+3]=e&255,n+4};m.prototype.writeBigInt64LE=function(e,n=0){return jr(this,e,n,-BigInt("0x8000000000000000"),BigInt("0x7fffffffffffffff"))};m.prototype.writeBigInt64BE=function(e,n=0){return Or(this,e,n,-BigInt("0x8000000000000000"),BigInt("0x7fffffffffffffff"))};function Rr(t,e,n,r,o,i){if(n+r>t.length)throw new RangeError("Index out of range");if(n<0)throw new RangeError("Index out of range")}function Pr(t,e,n,r,o){return e=+e,n=n>>>0,o||Rr(t,e,n,4,34028234663852886e22,-34028234663852886e22),$t(t,e,n,r,23,4),n+4}m.prototype.writeFloatLE=function(e,n,r){return Pr(this,e,n,!0,r)};m.prototype.writeFloatBE=function(e,n,r){return Pr(this,e,n,!1,r)};function Fr(t,e,n,r,o){return e=+e,n=n>>>0,o||Rr(t,e,n,8,17976931348623157e292,-17976931348623157e292),$t(t,e,n,r,52,8),n+8}m.prototype.writeDoubleLE=function(e,n,r){return Fr(this,e,n,!0,r)};m.prototype.writeDoubleBE=function(e,n,r){return Fr(this,e,n,!1,r)};m.prototype.copy=function(e,n,r,o){if(!m.isBuffer(e))throw new TypeError("argument should be a Buffer");if(r||(r=0),!o&&o!==0&&(o=this.length),n>=e.length&&(n=e.length),n||(n=0),o>0&&o<r&&(o=r),o===r||e.length===0||this.length===0)return 0;if(n<0)throw new RangeError("targetStart out of bounds");if(r<0||r>=this.length)throw new RangeError("Index out of range");if(o<0)throw new RangeError("sourceEnd out of bounds");o>this.length&&(o=this.length),e.length-n<o-r&&(o=e.length-n+r);let i=o-r;return this===e?this.copyWithin(n,r,o):Uint8Array.prototype.set.call(e,this.subarray(r,o),n),i};m.prototype.fill=function(e,n,r,o){if(typeof e=="string"){if(typeof n=="string"?(o=n,n=0,r=this.length):typeof r=="string"&&(o=r,r=this.length),o!==void 0&&typeof o!="string")throw new TypeError("encoding must be a string");if(typeof o=="string"&&!m.isEncoding(o))throw new TypeError("Unknown encoding: "+o);if(e.length===1){let s=e.charCodeAt(0);(o==="utf8"&&s<128||o==="latin1")&&(e=s)}}else typeof e=="number"?e=e&255:typeof e=="boolean"&&(e=Number(e));if(n<0||this.length<n||this.length<r)throw new RangeError("Out of range index");if(r<=n)return this;n=n>>>0,r=r===void 0?this.length:r>>>0,e||(e=0);let i;if(typeof e=="number")for(i=n;i<r;++i)this[i]=e;else{let s=m.isBuffer(e)?e:m.from(e,o),c=s.length;if(c===0)throw new TypeError('The value "'+e+'" is invalid for argument "value"');for(i=0;i<r-n;++i)this[i+n]=s[i%c]}return this};var Je={};function Yt(t,e,n){Je[t]=class extends n{constructor(){super(),Object.defineProperty(this,"message",{value:e.apply(this,arguments),writable:!0,configurable:!0}),this.name=`${this.name} [${t}]`,this.stack,delete this.name}get code(){return t}set code(o){Object.defineProperty(this,"code",{configurable:!0,enumerable:!0,value:o,writable:!0})}toString(){return`${this.name} [${t}]: ${this.message}`}}}Yt("ERR_BUFFER_OUT_OF_BOUNDS",function(t){return t?`${t} is outside of buffer bounds`:"Attempt to access memory outside buffer bounds"},RangeError);Yt("ERR_INVALID_ARG_TYPE",function(t,e){return`The "${t}" argument must be of type number. Received type ${typeof e}`},TypeError);Yt("ERR_OUT_OF_RANGE",function(t,e,n){let r=`The value of "${t}" is out of range.`,o=n;return Number.isInteger(n)&&Math.abs(n)>2**32?o=Ar(String(n)):typeof n=="bigint"&&(o=String(n),(n>BigInt(2)**BigInt(32)||n<-(BigInt(2)**BigInt(32)))&&(o=Ar(o)),o+="n"),r+=` It must be ${e}. Received ${o}`,r},RangeError);function Ar(t){let e="",n=t.length,r=t[0]==="-"?1:0;for(;n>=r+4;n-=3)e=`_${t.slice(n-3,n)}${e}`;return`${t.slice(0,n)}${e}`}function Bi(t,e,n){Ge(e,"offset"),(t[e]===void 0||t[e+n]===void 0)&&tt(e,t.length-(n+1))}function Dr(t,e,n,r,o,i){if(t>n||t<e){let s=typeof e=="bigint"?"n":"",c;throw i>3?e===0||e===BigInt(0)?c=`>= 0${s} and < 2${s} ** ${(i+1)*8}${s}`:c=`>= -(2${s} ** ${(i+1)*8-1}${s}) and < 2 ** ${(i+1)*8-1}${s}`:c=`>= ${e}${s} and <= ${n}${s}`,new Je.ERR_OUT_OF_RANGE("value",c,t)}Bi(r,o,i)}function Ge(t,e){if(typeof t!="number")throw new Je.ERR_INVALID_ARG_TYPE(e,"number",t)}function tt(t,e,n){throw Math.floor(t)!==t?(Ge(t,n),new Je.ERR_OUT_OF_RANGE(n||"offset","an integer",t)):e<0?new Je.ERR_BUFFER_OUT_OF_BOUNDS:new Je.ERR_OUT_OF_RANGE(n||"offset",`>= ${n?1:0} and <= ${e}`,t)}var zi=/[^+/0-9A-Za-z-_]/g;function Vi(t){if(t=t.split("=")[0],t=t.trim().replace(zi,""),t.length<2)return"";for(;t.length%4!==0;)t=t+"=";return t}function Wt(t,e){e=e||1/0;let n,r=t.length,o=null,i=[];for(let s=0;s<r;++s){if(n=t.charCodeAt(s),n>55295&&n<57344){if(!o){if(n>56319){(e-=3)>-1&&i.push(239,191,189);continue}else if(s+1===r){(e-=3)>-1&&i.push(239,191,189);continue}o=n;continue}if(n<56320){(e-=3)>-1&&i.push(239,191,189),o=n;continue}n=(o-55296<<10|n-56320)+65536}else o&&(e-=3)>-1&&i.push(239,191,189);if(o=null,n<128){if((e-=1)<0)break;i.push(n)}else if(n<2048){if((e-=2)<0)break;i.push(n>>6|192,n&63|128)}else if(n<65536){if((e-=3)<0)break;i.push(n>>12|224,n>>6&63|128,n&63|128)}else if(n<1114112){if((e-=4)<0)break;i.push(n>>18|240,n>>12&63|128,n>>6&63|128,n&63|128)}else throw new Error("Invalid code point")}return i}function Ji(t){let e=[];for(let n=0;n<t.length;++n)e.push(t.charCodeAt(n)&255);return e}function Gi(t,e){let n,r,o,i=[];for(let s=0;s<t.length&&!((e-=2)<0);++s)n=t.charCodeAt(s),r=n>>8,o=n%256,i.push(o),i.push(r);return i}function Ur(t){return Ir(Vi(t))}function bt(t,e,n,r){let o;for(o=0;o<r&&!(o+n>=e.length||o>=t.length);++o)e[o+n]=t[o];return o}var $i=function(){let t="0123456789abcdef",e=new Array(256);for(let n=0;n<16;++n){let r=n*16;for(let o=0;o<16;++o)e[r+o]=t[n]+t[o]}return e}();var Ft={};mi(Ft,{ArtMethod:()=>xt,ArtStackVisitor:()=>Mn,DVM_JNI_ENV_OFFSET_SELF:()=>ao,HandleVector:()=>ct,VariableSizedHandleScope:()=>lt,backtrace:()=>Zn,deoptimizeBootImage:()=>Yn,deoptimizeEverything:()=>Qn,deoptimizeMethod:()=>Kn,ensureClassInitialized:()=>hc,getAndroidApiLevel:()=>ee,getAndroidVersion:()=>dt,getApi:()=>J,getArtApexVersion:()=>Bn,getArtClassSpec:()=>Vn,getArtFieldSpec:()=>Rt,getArtMethodSpec:()=>_e,getArtThreadFromEnv:()=>Pt,getArtThreadSpec:()=>Ze,makeArtClassLoaderVisitor:()=>Hn,makeArtClassVisitor:()=>$n,makeMethodMangler:()=>al,makeObjectVisitorPredicate:()=>er,revertGlobalPatches:()=>qn,translateMethod:()=>cl,withAllArtThreadsSuspended:()=>Gn,withRunnableArtThread:()=>ye});var{pageSize:Xt,pointerSize:Hi}=Process,en=class{constructor(e){this.sliceSize=e,this.slicesPerPage=Xt/e,this.pages=[],this.free=[]}allocateSlice(e,n){let r=e.near===void 0,o=n===1;if(r&&o){let i=this.free.pop();if(i!==void 0)return i}else if(n<Xt){let{free:i}=this,s=i.length,c=o?null:ptr(n-1);for(let a=0;a!==s;a++){let l=i[a],d=r||this._isSliceNear(l,e),p=o||l.and(c).isNull();if(d&&p)return i.splice(a,1)[0]}}return this._allocatePage(e)}_allocatePage(e){let n=Memory.alloc(Xt,e),{sliceSize:r,slicesPerPage:o}=this;for(let i=1;i!==o;i++){let s=n.add(i*r);this.free.push(s)}return this.pages.push(n),n}_isSliceNear(e,n){let r=e.add(this.sliceSize),{near:o,maxDistance:i}=n,s=Br(o.sub(e)),c=Br(o.sub(r));return s.compare(i)<=0&&c.compare(i)<=0}freeSlice(e){this.free.push(e)}};function Br(t){let e=Hi===4?31:63,n=ptr(1).shl(e).not();return t.and(n)}function tn(t){return new en(t)}function de(t,e){if(e!==0)throw new Error(t+" failed: "+e)}var yt={v1_0:805371904,v1_2:805372416},Et={canTagObjects:1},{pointerSize:Zi}=Process,qi={exceptions:"propagate"};function Le(t,e){this.handle=t,this.vm=e,this.vtable=t.readPointer()}Le.prototype.deallocate=nt(47,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});Le.prototype.getLoadedClasses=nt(78,"int32",["pointer","pointer","pointer"],function(t,e,n){let r=t(this.handle,e,n);de("EnvJvmti::getLoadedClasses",r)});Le.prototype.iterateOverInstancesOfClass=nt(112,"int32",["pointer","pointer","int","pointer","pointer"],function(t,e,n,r,o){let i=t(this.handle,e,n,r,o);de("EnvJvmti::iterateOverInstancesOfClass",i)});Le.prototype.getObjectsWithTags=nt(114,"int32",["pointer","int","pointer","pointer","pointer","pointer"],function(t,e,n,r,o,i){let s=t(this.handle,e,n,r,o,i);de("EnvJvmti::getObjectsWithTags",s)});Le.prototype.addCapabilities=nt(142,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});function nt(t,e,n,r){let o=null;return function(){o===null&&(o=new NativeFunction(this.vtable.add((t-1)*Zi).readPointer(),e,n,qi));let i=[o];return i=i.concat.apply(i,arguments),r.apply(this,i)}}function xe(t,e,{limit:n}){let r=t,o=null;for(let i=0;i!==n;i++){let s=Instruction.parse(r),c=e(s,o);if(c!==null)return c;r=s.next,o=s}return null}function le(t){let e=null,n=!1;return function(...r){return n||(e=t(...r),n=!0),e}}function y(t,e){this.handle=t,this.vm=e}var vt=Process.pointerSize,Me=2,Wi=28,Ki=34,Qi=37,Yi=40,Xi=43,es=46,ts=49,ns=52,rs=55,os=58,is=61,ss=64,as=67,cs=70,ls=73,ds=76,us=79,ps=82,fs=85,hs=88,_s=91,ms=114,gs=117,bs=120,ys=123,Es=126,vs=129,Ss=132,ws=135,Is=138,Cs=141,Ts=95,As=96,Ls=97,xs=98,Ns=99,ks=100,Ms=101,js=102,Os=103,Rs=104,Ps=105,Fs=106,Ds=107,Us=108,Bs=109,zs=110,Vs=111,Js=112,Gs=145,$s=146,Hs=147,Zs=148,qs=149,Ws=150,Ks=151,Qs=152,Ys=153,Xs=154,ea=155,ta=156,na=157,ra=158,oa=159,ia=160,sa=161,aa=162,ca={pointer:Ki,uint8:Qi,int8:Yi,uint16:Xi,int16:es,int32:ts,int64:ns,float:rs,double:os,void:is},la={pointer:ss,uint8:as,int8:cs,uint16:ls,int16:ds,int32:us,int64:ps,float:fs,double:hs,void:_s},da={pointer:ms,uint8:gs,int8:bs,uint16:ys,int16:Es,int32:vs,int64:Ss,float:ws,double:Is,void:Cs},ua={pointer:Ts,uint8:As,int8:Ls,uint16:xs,int16:Ns,int32:ks,int64:Ms,float:js,double:Os},pa={pointer:Rs,uint8:Ps,int8:Fs,uint16:Ds,int16:Us,int32:Bs,int64:zs,float:Vs,double:Js},fa={pointer:Gs,uint8:$s,int8:Hs,uint16:Zs,int16:qs,int32:Ws,int64:Ks,float:Qs,double:Ys},ha={pointer:Xs,uint8:ea,int8:ta,uint16:na,int16:ra,int32:oa,int64:ia,float:sa,double:aa},Vr={exceptions:"propagate"},nn=null,hn=[];y.dispose=function(t){hn.forEach(t.deleteGlobalRef,t),hn=[]};function Pe(t){return hn.push(t),t}function St(t){return nn===null&&(nn=t.handle.readPointer()),nn}function x(t,e,n,r){let o=null;return function(){o===null&&(o=new NativeFunction(St(this).add(t*vt).readPointer(),e,n,Vr));let i=[o];return i=i.concat.apply(i,arguments),r.apply(this,i)}}y.prototype.getVersion=x(4,"int32",["pointer"],function(t){return t(this.handle)});y.prototype.findClass=x(6,"pointer",["pointer","pointer"],function(t,e){let n=t(this.handle,Memory.allocUtf8String(e));return this.throwIfExceptionPending(),n});y.prototype.throwIfExceptionPending=function(){let t=this.exceptionOccurred();if(t.isNull())return;this.exceptionClear();let e=this.newGlobalRef(t);this.deleteLocalRef(t);let n=this.vaMethod("pointer",[])(this.handle,e,this.javaLangObject().toString),r=this.stringFromJni(n);this.deleteLocalRef(n);let o=new Error(r);throw o.$h=e,Script.bindWeak(o,_a(this.vm,e)),o};function _a(t,e){return function(){t.perform(n=>{n.deleteGlobalRef(e)})}}y.prototype.fromReflectedMethod=x(7,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.fromReflectedField=x(8,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.toReflectedMethod=x(9,"pointer",["pointer","pointer","pointer","uint8"],function(t,e,n,r){return t(this.handle,e,n,r)});y.prototype.getSuperclass=x(10,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.isAssignableFrom=x(11,"uint8",["pointer","pointer","pointer"],function(t,e,n){return!!t(this.handle,e,n)});y.prototype.toReflectedField=x(12,"pointer",["pointer","pointer","pointer","uint8"],function(t,e,n,r){return t(this.handle,e,n,r)});y.prototype.throw=x(13,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.exceptionOccurred=x(15,"pointer",["pointer"],function(t){return t(this.handle)});y.prototype.exceptionDescribe=x(16,"void",["pointer"],function(t){t(this.handle)});y.prototype.exceptionClear=x(17,"void",["pointer"],function(t){t(this.handle)});y.prototype.pushLocalFrame=x(19,"int32",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.popLocalFrame=x(20,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.newGlobalRef=x(21,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.deleteGlobalRef=x(22,"void",["pointer","pointer"],function(t,e){t(this.handle,e)});y.prototype.deleteLocalRef=x(23,"void",["pointer","pointer"],function(t,e){t(this.handle,e)});y.prototype.isSameObject=x(24,"uint8",["pointer","pointer","pointer"],function(t,e,n){return!!t(this.handle,e,n)});y.prototype.newLocalRef=x(25,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.allocObject=x(27,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.getObjectClass=x(31,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.isInstanceOf=x(32,"uint8",["pointer","pointer","pointer"],function(t,e,n){return!!t(this.handle,e,n)});y.prototype.getMethodId=x(33,"pointer",["pointer","pointer","pointer","pointer"],function(t,e,n,r){return t(this.handle,e,Memory.allocUtf8String(n),Memory.allocUtf8String(r))});y.prototype.getFieldId=x(94,"pointer",["pointer","pointer","pointer","pointer"],function(t,e,n,r){return t(this.handle,e,Memory.allocUtf8String(n),Memory.allocUtf8String(r))});y.prototype.getIntField=x(100,"int32",["pointer","pointer","pointer"],function(t,e,n){return t(this.handle,e,n)});y.prototype.getStaticMethodId=x(113,"pointer",["pointer","pointer","pointer","pointer"],function(t,e,n,r){return t(this.handle,e,Memory.allocUtf8String(n),Memory.allocUtf8String(r))});y.prototype.getStaticFieldId=x(144,"pointer",["pointer","pointer","pointer","pointer"],function(t,e,n,r){return t(this.handle,e,Memory.allocUtf8String(n),Memory.allocUtf8String(r))});y.prototype.getStaticIntField=x(150,"int32",["pointer","pointer","pointer"],function(t,e,n){return t(this.handle,e,n)});y.prototype.getStringLength=x(164,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.getStringChars=x(165,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.releaseStringChars=x(166,"void",["pointer","pointer","pointer"],function(t,e,n){t(this.handle,e,n)});y.prototype.newStringUtf=x(167,"pointer",["pointer","pointer"],function(t,e){let n=Memory.allocUtf8String(e);return t(this.handle,n)});y.prototype.getStringUtfChars=x(169,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.releaseStringUtfChars=x(170,"void",["pointer","pointer","pointer"],function(t,e,n){t(this.handle,e,n)});y.prototype.getArrayLength=x(171,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.newObjectArray=x(172,"pointer",["pointer","int32","pointer","pointer"],function(t,e,n,r){return t(this.handle,e,n,r)});y.prototype.getObjectArrayElement=x(173,"pointer",["pointer","pointer","int32"],function(t,e,n){return t(this.handle,e,n)});y.prototype.setObjectArrayElement=x(174,"void",["pointer","pointer","int32","pointer"],function(t,e,n,r){t(this.handle,e,n,r)});y.prototype.newBooleanArray=x(175,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newByteArray=x(176,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newCharArray=x(177,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newShortArray=x(178,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newIntArray=x(179,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newLongArray=x(180,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newFloatArray=x(181,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.newDoubleArray=x(182,"pointer",["pointer","int32"],function(t,e){return t(this.handle,e)});y.prototype.getBooleanArrayElements=x(183,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getByteArrayElements=x(184,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getCharArrayElements=x(185,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getShortArrayElements=x(186,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getIntArrayElements=x(187,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getLongArrayElements=x(188,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getFloatArrayElements=x(189,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.getDoubleArrayElements=x(190,"pointer",["pointer","pointer","pointer"],function(t,e){return t(this.handle,e,NULL)});y.prototype.releaseBooleanArrayElements=x(191,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseByteArrayElements=x(192,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseCharArrayElements=x(193,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseShortArrayElements=x(194,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseIntArrayElements=x(195,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseLongArrayElements=x(196,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseFloatArrayElements=x(197,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.releaseDoubleArrayElements=x(198,"pointer",["pointer","pointer","pointer","int32"],function(t,e,n){t(this.handle,e,n,Me)});y.prototype.getByteArrayRegion=x(200,"void",["pointer","pointer","int","int","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setBooleanArrayRegion=x(207,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setByteArrayRegion=x(208,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setCharArrayRegion=x(209,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setShortArrayRegion=x(210,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setIntArrayRegion=x(211,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setLongArrayRegion=x(212,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setFloatArrayRegion=x(213,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.setDoubleArrayRegion=x(214,"void",["pointer","pointer","int32","int32","pointer"],function(t,e,n,r,o){t(this.handle,e,n,r,o)});y.prototype.registerNatives=x(215,"int32",["pointer","pointer","pointer","int32"],function(t,e,n,r){return t(this.handle,e,n,r)});y.prototype.monitorEnter=x(217,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.monitorExit=x(218,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.getDirectBufferAddress=x(230,"pointer",["pointer","pointer"],function(t,e){return t(this.handle,e)});y.prototype.getObjectRefType=x(232,"int32",["pointer","pointer"],function(t,e){return t(this.handle,e)});var zr=new Map;function wt(t,e,n,r){return mn(this,"p",ga,t,e,n,r)}function _n(t,e,n,r){return mn(this,"v",ba,t,e,n,r)}function ma(t,e,n,r){return mn(this,"n",ya,t,e,n,r)}function mn(t,e,n,r,o,i,s){if(s!==void 0)return n(t,r,o,i,s);let c=[r,e,o].concat(i).join("|"),a=zr.get(c);return a===void 0&&(a=n(t,r,o,i,Vr),zr.set(c,a)),a}function ga(t,e,n,r,o){return new NativeFunction(St(t).add(e*vt).readPointer(),n,["pointer","pointer","pointer"].concat(r),o)}function ba(t,e,n,r,o){return new NativeFunction(St(t).add(e*vt).readPointer(),n,["pointer","pointer","pointer","..."].concat(r),o)}function ya(t,e,n,r,o){return new NativeFunction(St(t).add(e*vt).readPointer(),n,["pointer","pointer","pointer","pointer","..."].concat(r),o)}y.prototype.constructor=function(t,e){return _n.call(this,Wi,"pointer",t,e)};y.prototype.vaMethod=function(t,e,n){let r=ca[t];if(r===void 0)throw new Error("Unsupported type: "+t);return _n.call(this,r,t,e,n)};y.prototype.nonvirtualVaMethod=function(t,e,n){let r=la[t];if(r===void 0)throw new Error("Unsupported type: "+t);return ma.call(this,r,t,e,n)};y.prototype.staticVaMethod=function(t,e,n){let r=da[t];if(r===void 0)throw new Error("Unsupported type: "+t);return _n.call(this,r,t,e,n)};y.prototype.getField=function(t){let e=ua[t];if(e===void 0)throw new Error("Unsupported type: "+t);return wt.call(this,e,t,[])};y.prototype.getStaticField=function(t){let e=fa[t];if(e===void 0)throw new Error("Unsupported type: "+t);return wt.call(this,e,t,[])};y.prototype.setField=function(t){let e=pa[t];if(e===void 0)throw new Error("Unsupported type: "+t);return wt.call(this,e,"void",[t])};y.prototype.setStaticField=function(t){let e=ha[t];if(e===void 0)throw new Error("Unsupported type: "+t);return wt.call(this,e,"void",[t])};var rn=null;y.prototype.javaLangClass=function(){if(rn===null){let t=this.findClass("java/lang/Class");try{let e=this.getMethodId.bind(this,t);rn={handle:Pe(this.newGlobalRef(t)),getName:e("getName","()Ljava/lang/String;"),getSimpleName:e("getSimpleName","()Ljava/lang/String;"),getGenericSuperclass:e("getGenericSuperclass","()Ljava/lang/reflect/Type;"),getDeclaredConstructors:e("getDeclaredConstructors","()[Ljava/lang/reflect/Constructor;"),getDeclaredMethods:e("getDeclaredMethods","()[Ljava/lang/reflect/Method;"),getDeclaredFields:e("getDeclaredFields","()[Ljava/lang/reflect/Field;"),isArray:e("isArray","()Z"),isPrimitive:e("isPrimitive","()Z"),isInterface:e("isInterface","()Z"),getComponentType:e("getComponentType","()Ljava/lang/Class;")}}finally{this.deleteLocalRef(t)}}return rn};var on=null;y.prototype.javaLangObject=function(){if(on===null){let t=this.findClass("java/lang/Object");try{let e=this.getMethodId.bind(this,t);on={handle:Pe(this.newGlobalRef(t)),toString:e("toString","()Ljava/lang/String;"),getClass:e("getClass","()Ljava/lang/Class;")}}finally{this.deleteLocalRef(t)}}return on};var sn=null;y.prototype.javaLangReflectConstructor=function(){if(sn===null){let t=this.findClass("java/lang/reflect/Constructor");try{sn={getGenericParameterTypes:this.getMethodId(t,"getGenericParameterTypes","()[Ljava/lang/reflect/Type;")}}finally{this.deleteLocalRef(t)}}return sn};var an=null;y.prototype.javaLangReflectMethod=function(){if(an===null){let t=this.findClass("java/lang/reflect/Method");try{let e=this.getMethodId.bind(this,t);an={getName:e("getName","()Ljava/lang/String;"),getGenericParameterTypes:e("getGenericParameterTypes","()[Ljava/lang/reflect/Type;"),getParameterTypes:e("getParameterTypes","()[Ljava/lang/Class;"),getGenericReturnType:e("getGenericReturnType","()Ljava/lang/reflect/Type;"),getGenericExceptionTypes:e("getGenericExceptionTypes","()[Ljava/lang/reflect/Type;"),getModifiers:e("getModifiers","()I"),isVarArgs:e("isVarArgs","()Z")}}finally{this.deleteLocalRef(t)}}return an};var cn=null;y.prototype.javaLangReflectField=function(){if(cn===null){let t=this.findClass("java/lang/reflect/Field");try{let e=this.getMethodId.bind(this,t);cn={getName:e("getName","()Ljava/lang/String;"),getType:e("getType","()Ljava/lang/Class;"),getGenericType:e("getGenericType","()Ljava/lang/reflect/Type;"),getModifiers:e("getModifiers","()I"),toString:e("toString","()Ljava/lang/String;")}}finally{this.deleteLocalRef(t)}}return cn};var ln=null;y.prototype.javaLangReflectTypeVariable=function(){if(ln===null){let t=this.findClass("java/lang/reflect/TypeVariable");try{let e=this.getMethodId.bind(this,t);ln={handle:Pe(this.newGlobalRef(t)),getName:e("getName","()Ljava/lang/String;"),getBounds:e("getBounds","()[Ljava/lang/reflect/Type;"),getGenericDeclaration:e("getGenericDeclaration","()Ljava/lang/reflect/GenericDeclaration;")}}finally{this.deleteLocalRef(t)}}return ln};var dn=null;y.prototype.javaLangReflectWildcardType=function(){if(dn===null){let t=this.findClass("java/lang/reflect/WildcardType");try{let e=this.getMethodId.bind(this,t);dn={handle:Pe(this.newGlobalRef(t)),getLowerBounds:e("getLowerBounds","()[Ljava/lang/reflect/Type;"),getUpperBounds:e("getUpperBounds","()[Ljava/lang/reflect/Type;")}}finally{this.deleteLocalRef(t)}}return dn};var un=null;y.prototype.javaLangReflectGenericArrayType=function(){if(un===null){let t=this.findClass("java/lang/reflect/GenericArrayType");try{un={handle:Pe(this.newGlobalRef(t)),getGenericComponentType:this.getMethodId(t,"getGenericComponentType","()Ljava/lang/reflect/Type;")}}finally{this.deleteLocalRef(t)}}return un};var pn=null;y.prototype.javaLangReflectParameterizedType=function(){if(pn===null){let t=this.findClass("java/lang/reflect/ParameterizedType");try{let e=this.getMethodId.bind(this,t);pn={handle:Pe(this.newGlobalRef(t)),getActualTypeArguments:e("getActualTypeArguments","()[Ljava/lang/reflect/Type;"),getRawType:e("getRawType","()Ljava/lang/reflect/Type;"),getOwnerType:e("getOwnerType","()Ljava/lang/reflect/Type;")}}finally{this.deleteLocalRef(t)}}return pn};var fn=null;y.prototype.javaLangString=function(){if(fn===null){let t=this.findClass("java/lang/String");try{fn={handle:Pe(this.newGlobalRef(t))}}finally{this.deleteLocalRef(t)}}return fn};y.prototype.getClassName=function(t){let e=this.vaMethod("pointer",[])(this.handle,t,this.javaLangClass().getName);try{return this.stringFromJni(e)}finally{this.deleteLocalRef(e)}};y.prototype.getObjectClassName=function(t){let e=this.getObjectClass(t);try{return this.getClassName(e)}finally{this.deleteLocalRef(e)}};y.prototype.getActualTypeArgument=function(t){let e=this.vaMethod("pointer",[])(this.handle,t,this.javaLangReflectParameterizedType().getActualTypeArguments);if(this.throwIfExceptionPending(),!e.isNull())try{return this.getTypeNameFromFirstTypeElement(e)}finally{this.deleteLocalRef(e)}};y.prototype.getTypeNameFromFirstTypeElement=function(t){if(this.getArrayLength(t)>0){let n=this.getObjectArrayElement(t,0);try{return this.getTypeName(n)}finally{this.deleteLocalRef(n)}}else return"java.lang.Object"};y.prototype.getTypeName=function(t,e){let n=this.vaMethod("pointer",[]);if(this.isInstanceOf(t,this.javaLangClass().handle))return this.getClassName(t);if(this.isInstanceOf(t,this.javaLangReflectGenericArrayType().handle))return this.getArrayTypeName(t);if(this.isInstanceOf(t,this.javaLangReflectParameterizedType().handle)){let r=n(this.handle,t,this.javaLangReflectParameterizedType().getRawType);this.throwIfExceptionPending();let o;try{o=this.getTypeName(r)}finally{this.deleteLocalRef(r)}return e&&(o+="<"+this.getActualTypeArgument(t)+">"),o}else return this.isInstanceOf(t,this.javaLangReflectTypeVariable().handle)||this.isInstanceOf(t,this.javaLangReflectWildcardType().handle),"java.lang.Object"};y.prototype.getArrayTypeName=function(t){let e=this.vaMethod("pointer",[]);if(this.isInstanceOf(t,this.javaLangClass().handle))return this.getClassName(t);if(this.isInstanceOf(t,this.javaLangReflectGenericArrayType().handle)){let n=e(this.handle,t,this.javaLangReflectGenericArrayType().getGenericComponentType);this.throwIfExceptionPending();try{return"[L"+this.getTypeName(n)+";"}finally{this.deleteLocalRef(n)}}else return"[Ljava.lang.Object;"};y.prototype.stringFromJni=function(t){let e=this.getStringChars(t);if(e.isNull())throw new Error("Unable to access string");try{let n=this.getStringLength(t);return e.readUtf16String(n)}finally{this.releaseStringChars(t,e)}};var Jr=65542,$e=Process.pointerSize,gn=Process.getCurrentThreadId(),Fe=new Map,rt=new Map;function Ie(t){let e=t.vm,n=null,r=null,o=null;function i(){let c=e.readPointer(),a={exceptions:"propagate"};n=new NativeFunction(c.add(4*$e).readPointer(),"int32",["pointer","pointer","pointer"],a),r=new NativeFunction(c.add(5*$e).readPointer(),"int32",["pointer"],a),o=new NativeFunction(c.add(6*$e).readPointer(),"int32",["pointer","pointer","int32"],a)}this.handle=e,this.perform=function(c){let a=Process.getCurrentThreadId(),l=s(a);if(l!==null)return c(l);let d=this._tryGetEnv(),p=d!==null;p||(d=this.attachCurrentThread(),Fe.set(a,!0)),this.link(a,d);try{return c(d)}finally{let f=a===gn;if(f||this.unlink(a),!p&&!f){let u=Fe.get(a);Fe.delete(a),u&&this.detachCurrentThread()}}},this.attachCurrentThread=function(){let c=Memory.alloc($e);return de("VM::AttachCurrentThread",n(e,c,NULL)),new y(c.readPointer(),this)},this.detachCurrentThread=function(){de("VM::DetachCurrentThread",r(e))},this.preventDetachDueToClassLoader=function(){let c=Process.getCurrentThreadId();Fe.has(c)&&Fe.set(c,!1)},this.getEnv=function(){let c=s(Process.getCurrentThreadId());if(c!==null)return c;let a=Memory.alloc($e),l=o(e,a,Jr);if(l===-2)throw new Error("Current thread is not attached to the Java VM; please move this code inside a Java.perform() callback");return de("VM::GetEnv",l),new y(a.readPointer(),this)},this.tryGetEnv=function(){let c=s(Process.getCurrentThreadId());return c!==null?c:this._tryGetEnv()},this._tryGetEnv=function(){let c=this.tryGetEnvHandle(Jr);return c===null?null:new y(c,this)},this.tryGetEnvHandle=function(c){let a=Memory.alloc($e);return o(e,a,c)!==0?null:a.readPointer()},this.makeHandleDestructor=function(c){return()=>{this.perform(a=>{a.deleteGlobalRef(c)})}},this.link=function(c,a){let l=rt.get(c);l===void 0?rt.set(c,[a,1]):l[1]++},this.unlink=function(c){let a=rt.get(c);a[1]===1?rt.delete(c):a[1]--};function s(c){let a=rt.get(c);return a===void 0?null:a[0]}i.call(this)}Ie.dispose=function(t){Fe.get(gn)===!0&&(Fe.delete(gn),t.detachCurrentThread())};var Ea=4,v=Process.pointerSize,{readU32:va,readPointer:Sa,writeU32:wa,writePointer:Ia}=NativePointer.prototype,Ca=1,Ta=8,Aa=16,At=256,La=524288,xa=2097152,so=1073741824,Na=524288,ka=134217728,Gr=1048576,Ma=2097152,ja=268435456,Oa=268435456,Ra=0,Ln=3,xn=5,Un=ptr(1).not(),Pa=2147467263,Fa=4294963200,Ot=17*v,Da=18*v,ao=12,Ua=112,Ba=116,za=0,yn=56,$r=4,Va=8,Ja=10,Ga=12,$a=14,Ha=28,Za=36,qa=0,Wa=1,Ka=2,Qa=3,Ya=4,Xa=5,ec=6,tc=7,Hr=2147483648,nc=28,at=3*v,rc=3*v,oc=1,ic=1,co=le(mc),sc=le(xc),_e=le(kc),Ze=le(Mc),ac=le(jc),cc=le(Vc),dt=le(Fc),lo=le(Dc),ee=le(Uc),Bn=le(Bc),lc=le(Hc),dc=Process.arch==="ia32"?Ml:kl,W={exceptions:"propagate"},ot={},En=null,vn=null,uo=null,oe=null,zn=[],Lt=new Map,po=[],Sn=null,Zr=0,qr=!1,Wr=!1,it=null,uc=[],wn=null,It=null;function J(){return En===null&&(En=pc()),En}function pc(){let t=Process.enumerateModules().filter(u=>/^lib(art|dvm).so$/.test(u.name)).filter(u=>!/\/system\/fake-libs/.test(u.path));if(t.length===0)return null;let e=t[0],n=e.name.indexOf("art")!==-1?"art":"dalvik",r=n==="art",o={module:e,find(u){let{module:_}=this,h=_.findExportByName(u);return h===null&&(h=_.findSymbolByName(u)),h},flavor:n,addLocalReference:null};o.isApiLevel34OrApexEquivalent=r&&(o.find("_ZN3art7AppInfo29GetPrimaryApkReferenceProfileEv")!==null||o.find("_ZN3art6Thread15RunFlipFunctionEPS0_")!==null);let i=r?{functions:{JNI_GetCreatedJavaVMs:["JNI_GetCreatedJavaVMs","int",["pointer","int","pointer"]],artInterpreterToCompiledCodeBridge:function(u){this.artInterpreterToCompiledCodeBridge=u},_ZN3art9JavaVMExt12AddGlobalRefEPNS_6ThreadENS_6ObjPtrINS_6mirror6ObjectEEE:["art::JavaVMExt::AddGlobalRef","pointer",["pointer","pointer","pointer"]],_ZN3art9JavaVMExt12AddGlobalRefEPNS_6ThreadEPNS_6mirror6ObjectE:["art::JavaVMExt::AddGlobalRef","pointer",["pointer","pointer","pointer"]],_ZN3art17ReaderWriterMutex13ExclusiveLockEPNS_6ThreadE:["art::ReaderWriterMutex::ExclusiveLock","void",["pointer","pointer"]],_ZN3art17ReaderWriterMutex15ExclusiveUnlockEPNS_6ThreadE:["art::ReaderWriterMutex::ExclusiveUnlock","void",["pointer","pointer"]],_ZN3art22IndirectReferenceTable3AddEjPNS_6mirror6ObjectE:function(u){this["art::IndirectReferenceTable::Add"]=new NativeFunction(u,"pointer",["pointer","uint","pointer"],W)},_ZN3art22IndirectReferenceTable3AddENS_15IRTSegmentStateENS_6ObjPtrINS_6mirror6ObjectEEE:function(u){this["art::IndirectReferenceTable::Add"]=new NativeFunction(u,"pointer",["pointer","uint","pointer"],W)},_ZN3art9JavaVMExt12DecodeGlobalEPv:function(u){let _;ee()>=26?_=dc(u,["pointer","pointer"]):_=new NativeFunction(u,"pointer",["pointer","pointer"],W),this["art::JavaVMExt::DecodeGlobal"]=function(h,g,b){return _(h,b)}},_ZN3art9JavaVMExt12DecodeGlobalEPNS_6ThreadEPv:["art::JavaVMExt::DecodeGlobal","pointer",["pointer","pointer","pointer"]],_ZNK3art6Thread19DecodeGlobalJObjectEP8_jobject:["art::Thread::DecodeJObject","pointer",["pointer","pointer"]],_ZNK3art6Thread13DecodeJObjectEP8_jobject:["art::Thread::DecodeJObject","pointer",["pointer","pointer"]],_ZN3art10ThreadList10SuspendAllEPKcb:["art::ThreadList::SuspendAll","void",["pointer","pointer","bool"]],_ZN3art10ThreadList10SuspendAllEv:function(u){let _=new NativeFunction(u,"void",["pointer"],W);this["art::ThreadList::SuspendAll"]=function(h,g,b){return _(h)}},_ZN3art10ThreadList9ResumeAllEv:["art::ThreadList::ResumeAll","void",["pointer"]],_ZN3art11ClassLinker12VisitClassesEPNS_12ClassVisitorE:["art::ClassLinker::VisitClasses","void",["pointer","pointer"]],_ZN3art11ClassLinker12VisitClassesEPFbPNS_6mirror5ClassEPvES4_:function(u){let _=new NativeFunction(u,"void",["pointer","pointer","pointer"],W);this["art::ClassLinker::VisitClasses"]=function(h,g){_(h,g,NULL)}},_ZNK3art11ClassLinker17VisitClassLoadersEPNS_18ClassLoaderVisitorE:["art::ClassLinker::VisitClassLoaders","void",["pointer","pointer"]],_ZN3art2gc4Heap12VisitObjectsEPFvPNS_6mirror6ObjectEPvES5_:["art::gc::Heap::VisitObjects","void",["pointer","pointer","pointer"]],_ZN3art2gc4Heap12GetInstancesERNS_24VariableSizedHandleScopeENS_6HandleINS_6mirror5ClassEEEiRNSt3__16vectorINS4_INS5_6ObjectEEENS8_9allocatorISB_EEEE:["art::gc::Heap::GetInstances","void",["pointer","pointer","pointer","int","pointer"]],_ZN3art2gc4Heap12GetInstancesERNS_24VariableSizedHandleScopeENS_6HandleINS_6mirror5ClassEEEbiRNSt3__16vectorINS4_INS5_6ObjectEEENS8_9allocatorISB_EEEE:function(u){let _=new NativeFunction(u,"void",["pointer","pointer","pointer","bool","int","pointer"],W);this["art::gc::Heap::GetInstances"]=function(h,g,b,E,C){_(h,g,b,0,E,C)}},_ZN3art12StackVisitorC2EPNS_6ThreadEPNS_7ContextENS0_13StackWalkKindEjb:["art::StackVisitor::StackVisitor","void",["pointer","pointer","pointer","uint","uint","bool"]],_ZN3art12StackVisitorC2EPNS_6ThreadEPNS_7ContextENS0_13StackWalkKindEmb:["art::StackVisitor::StackVisitor","void",["pointer","pointer","pointer","uint","size_t","bool"]],_ZN3art12StackVisitor9WalkStackILNS0_16CountTransitionsE0EEEvb:["art::StackVisitor::WalkStack","void",["pointer","bool"]],_ZNK3art12StackVisitor9GetMethodEv:["art::StackVisitor::GetMethod","pointer",["pointer"]],_ZNK3art12StackVisitor16DescribeLocationEv:function(u){this["art::StackVisitor::DescribeLocation"]=Tt(u,["pointer"])},_ZNK3art12StackVisitor24GetCurrentQuickFrameInfoEv:function(u){this["art::StackVisitor::GetCurrentQuickFrameInfo"]=$c(u)},_ZN3art7Context6CreateEv:["art::Context::Create","pointer",[]],_ZN3art6Thread18GetLongJumpContextEv:["art::Thread::GetLongJumpContext","pointer",["pointer"]],_ZN3art6mirror5Class13GetDescriptorEPNSt3__112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE:function(u){this["art::mirror::Class::GetDescriptor"]=u},_ZN3art6mirror5Class11GetLocationEv:function(u){this["art::mirror::Class::GetLocation"]=Tt(u,["pointer"])},_ZN3art9ArtMethod12PrettyMethodEb:function(u){this["art::ArtMethod::PrettyMethod"]=Tt(u,["pointer","bool"])},_ZN3art12PrettyMethodEPNS_9ArtMethodEb:function(u){this["art::ArtMethod::PrettyMethodNullSafe"]=Tt(u,["pointer","bool"])},_ZN3art6Thread14CurrentFromGdbEv:["art::Thread::CurrentFromGdb","pointer",[]],_ZN3art6mirror6Object5CloneEPNS_6ThreadE:function(u){this["art::mirror::Object::Clone"]=new NativeFunction(u,"pointer",["pointer","pointer"],W)},_ZN3art6mirror6Object5CloneEPNS_6ThreadEm:function(u){let _=new NativeFunction(u,"pointer",["pointer","pointer","pointer"],W);this["art::mirror::Object::Clone"]=function(h,g){let b=NULL;return _(h,g,b)}},_ZN3art6mirror6Object5CloneEPNS_6ThreadEj:function(u){let _=new NativeFunction(u,"pointer",["pointer","pointer","uint"],W);this["art::mirror::Object::Clone"]=function(h,g){return _(h,g,0)}},_ZN3art3Dbg14SetJdwpAllowedEb:["art::Dbg::SetJdwpAllowed","void",["bool"]],_ZN3art3Dbg13ConfigureJdwpERKNS_4JDWP11JdwpOptionsE:["art::Dbg::ConfigureJdwp","void",["pointer"]],_ZN3art31InternalDebuggerControlCallback13StartDebuggerEv:["art::InternalDebuggerControlCallback::StartDebugger","void",["pointer"]],_ZN3art3Dbg9StartJdwpEv:["art::Dbg::StartJdwp","void",[]],_ZN3art3Dbg8GoActiveEv:["art::Dbg::GoActive","void",[]],_ZN3art3Dbg21RequestDeoptimizationERKNS_21DeoptimizationRequestE:["art::Dbg::RequestDeoptimization","void",["pointer"]],_ZN3art3Dbg20ManageDeoptimizationEv:["art::Dbg::ManageDeoptimization","void",[]],_ZN3art15instrumentation15Instrumentation20EnableDeoptimizationEv:["art::Instrumentation::EnableDeoptimization","void",["pointer"]],_ZN3art15instrumentation15Instrumentation20DeoptimizeEverythingEPKc:["art::Instrumentation::DeoptimizeEverything","void",["pointer","pointer"]],_ZN3art15instrumentation15Instrumentation20DeoptimizeEverythingEv:function(u){let _=new NativeFunction(u,"void",["pointer"],W);this["art::Instrumentation::DeoptimizeEverything"]=function(h,g){_(h)}},_ZN3art7Runtime19DeoptimizeBootImageEv:["art::Runtime::DeoptimizeBootImage","void",["pointer"]],_ZN3art15instrumentation15Instrumentation10DeoptimizeEPNS_9ArtMethodE:["art::Instrumentation::Deoptimize","void",["pointer","pointer"]],_ZN3art3jni12JniIdManager14DecodeMethodIdEP10_jmethodID:["art::jni::JniIdManager::DecodeMethodId","pointer",["pointer","pointer"]],_ZN3art3jni12JniIdManager13DecodeFieldIdEP9_jfieldID:["art::jni::JniIdManager::DecodeFieldId","pointer",["pointer","pointer"]],_ZN3art11interpreter18GetNterpEntryPointEv:["art::interpreter::GetNterpEntryPoint","pointer",[]],_ZN3art7Monitor17TranslateLocationEPNS_9ArtMethodEjPPKcPi:["art::Monitor::TranslateLocation","void",["pointer","uint32","pointer","pointer"]]},variables:{_ZN3art3Dbg9gRegistryE:function(u){this.isJdwpStarted=()=>!u.readPointer().isNull()},_ZN3art3Dbg15gDebuggerActiveE:function(u){this.isDebuggerActive=()=>!!u.readU8()}},optionals:new Set(["artInterpreterToCompiledCodeBridge","_ZN3art9JavaVMExt12AddGlobalRefEPNS_6ThreadENS_6ObjPtrINS_6mirror6ObjectEEE","_ZN3art9JavaVMExt12AddGlobalRefEPNS_6ThreadEPNS_6mirror6ObjectE","_ZN3art9JavaVMExt12DecodeGlobalEPv","_ZN3art9JavaVMExt12DecodeGlobalEPNS_6ThreadEPv","_ZNK3art6Thread19DecodeGlobalJObjectEP8_jobject","_ZNK3art6Thread13DecodeJObjectEP8_jobject","_ZN3art10ThreadList10SuspendAllEPKcb","_ZN3art10ThreadList10SuspendAllEv","_ZN3art11ClassLinker12VisitClassesEPNS_12ClassVisitorE","_ZN3art11ClassLinker12VisitClassesEPFbPNS_6mirror5ClassEPvES4_","_ZNK3art11ClassLinker17VisitClassLoadersEPNS_18ClassLoaderVisitorE","_ZN3art6mirror6Object5CloneEPNS_6ThreadE","_ZN3art6mirror6Object5CloneEPNS_6ThreadEm","_ZN3art6mirror6Object5CloneEPNS_6ThreadEj","_ZN3art22IndirectReferenceTable3AddEjPNS_6mirror6ObjectE","_ZN3art22IndirectReferenceTable3AddENS_15IRTSegmentStateENS_6ObjPtrINS_6mirror6ObjectEEE","_ZN3art2gc4Heap12VisitObjectsEPFvPNS_6mirror6ObjectEPvES5_","_ZN3art2gc4Heap12GetInstancesERNS_24VariableSizedHandleScopeENS_6HandleINS_6mirror5ClassEEEiRNSt3__16vectorINS4_INS5_6ObjectEEENS8_9allocatorISB_EEEE","_ZN3art2gc4Heap12GetInstancesERNS_24VariableSizedHandleScopeENS_6HandleINS_6mirror5ClassEEEbiRNSt3__16vectorINS4_INS5_6ObjectEEENS8_9allocatorISB_EEEE","_ZN3art12StackVisitorC2EPNS_6ThreadEPNS_7ContextENS0_13StackWalkKindEjb","_ZN3art12StackVisitorC2EPNS_6ThreadEPNS_7ContextENS0_13StackWalkKindEmb","_ZN3art12StackVisitor9WalkStackILNS0_16CountTransitionsE0EEEvb","_ZNK3art12StackVisitor9GetMethodEv","_ZNK3art12StackVisitor16DescribeLocationEv","_ZNK3art12StackVisitor24GetCurrentQuickFrameInfoEv","_ZN3art7Context6CreateEv","_ZN3art6Thread18GetLongJumpContextEv","_ZN3art6mirror5Class13GetDescriptorEPNSt3__112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE","_ZN3art6mirror5Class11GetLocationEv","_ZN3art9ArtMethod12PrettyMethodEb","_ZN3art12PrettyMethodEPNS_9ArtMethodEb","_ZN3art3Dbg13ConfigureJdwpERKNS_4JDWP11JdwpOptionsE","_ZN3art31InternalDebuggerControlCallback13StartDebuggerEv","_ZN3art3Dbg15gDebuggerActiveE","_ZN3art15instrumentation15Instrumentation20EnableDeoptimizationEv","_ZN3art15instrumentation15Instrumentation20DeoptimizeEverythingEPKc","_ZN3art15instrumentation15Instrumentation20DeoptimizeEverythingEv","_ZN3art7Runtime19DeoptimizeBootImageEv","_ZN3art15instrumentation15Instrumentation10DeoptimizeEPNS_9ArtMethodE","_ZN3art3Dbg9StartJdwpEv","_ZN3art3Dbg8GoActiveEv","_ZN3art3Dbg21RequestDeoptimizationERKNS_21DeoptimizationRequestE","_ZN3art3Dbg20ManageDeoptimizationEv","_ZN3art3Dbg9gRegistryE","_ZN3art3jni12JniIdManager14DecodeMethodIdEP10_jmethodID","_ZN3art3jni12JniIdManager13DecodeFieldIdEP9_jfieldID","_ZN3art11interpreter18GetNterpEntryPointEv","_ZN3art7Monitor17TranslateLocationEPNS_9ArtMethodEjPPKcPi"])}:{functions:{_Z20dvmDecodeIndirectRefP6ThreadP8_jobject:["dvmDecodeIndirectRef","pointer",["pointer","pointer"]],_Z15dvmUseJNIBridgeP6MethodPv:["dvmUseJNIBridge","void",["pointer","pointer"]],_Z20dvmHeapSourceGetBasev:["dvmHeapSourceGetBase","pointer",[]],_Z21dvmHeapSourceGetLimitv:["dvmHeapSourceGetLimit","pointer",[]],_Z16dvmIsValidObjectPK6Object:["dvmIsValidObject","uint8",["pointer"]],JNI_GetCreatedJavaVMs:["JNI_GetCreatedJavaVMs","int",["pointer","int","pointer"]]},variables:{gDvmJni:function(u){this.gDvmJni=u},gDvm:function(u){this.gDvm=u}}},{functions:s={},variables:c={},optionals:a=new Set}=i,l=[];for(let[u,_]of Object.entries(s)){let h=o.find(u);h!==null?typeof _=="function"?_.call(o,h):o[_[0]]=new NativeFunction(h,_[1],_[2],W):a.has(u)||l.push(u)}for(let[u,_]of Object.entries(c)){let h=o.find(u);h!==null?_.call(o,h):a.has(u)||l.push(u)}if(l.length>0)throw new Error("Java API only partially available; please file a bug. Missing: "+l.join(", "));let d=Memory.alloc(v),p=Memory.alloc(Ea);if(de("JNI_GetCreatedJavaVMs",o.JNI_GetCreatedJavaVMs(d,1,p)),p.readInt()===0)return null;if(o.vm=d.readPointer(),r){let u=ee(),_;u>=27?_=33554432:u>=24?_=16777216:_=0,o.kAccCompileDontBother=_;let h=o.vm.add(v).readPointer();o.artRuntime=h;let g=co(o),b=g.offset,E=b.instrumentation;o.artInstrumentation=E!==null?h.add(E):null,Bn()>=36e7&&o.artInstrumentation!=null&&(o.artInstrumentation=o.artInstrumentation.readPointer()),o.artHeap=h.add(b.heap).readPointer(),o.artThreadList=h.add(b.threadList).readPointer();let A=h.add(b.classLinker).readPointer(),M=Nc(h,g).offset,O=A.add(M.quickResolutionTrampoline).readPointer(),k=A.add(M.quickImtConflictTrampoline).readPointer(),N=A.add(M.quickGenericJniTrampoline).readPointer(),S=A.add(M.quickToInterpreterBridgeTrampoline).readPointer();o.artClassLinker={address:A,quickResolutionTrampoline:O,quickImtConflictTrampoline:k,quickGenericJniTrampoline:N,quickToInterpreterBridgeTrampoline:S};let L=new Ie(o);o.artQuickGenericJniTrampoline=In(N,L),o.artQuickToInterpreterBridge=In(S,L),o.artQuickResolutionTrampoline=In(O,L),o["art::JavaVMExt::AddGlobalRef"]===void 0&&(o["art::JavaVMExt::AddGlobalRef"]=Cl(o)),o["art::JavaVMExt::DecodeGlobal"]===void 0&&(o["art::JavaVMExt::DecodeGlobal"]=Tl(o)),o["art::ArtMethod::PrettyMethod"]===void 0&&(o["art::ArtMethod::PrettyMethod"]=o["art::ArtMethod::PrettyMethodNullSafe"]),o["art::interpreter::GetNterpEntryPoint"]!==void 0?o.artNterpEntryPoint=o["art::interpreter::GetNterpEntryPoint"]():o.artNterpEntryPoint=o.find("ExecuteNterpImpl"),oe=Wc(o,L),Nl(o);let j=null;Object.defineProperty(o,"jvmti",{get(){return j===null&&(j=[fc(L,this.artRuntime)]),j[0]}})}let f=e.enumerateImports().filter(u=>u.name.indexOf("_Z")===0).reduce((u,_)=>(u[_.name]=_.address,u),{});return o.$new=new NativeFunction(f._Znwm||f._Znwj,"pointer",["ulong"],W),o.$delete=new NativeFunction(f._ZdlPv,"void",["pointer"],W),uo=r?Rn:Pn,o}function fc(t,e){let n=null;return t.perform(()=>{let r=J().find("_ZN3art7Runtime18EnsurePluginLoadedEPKcPNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEE");if(r===null)return;let o=new NativeFunction(r,"bool",["pointer","pointer","pointer"]),i=Memory.alloc(v);if(!o(e,Memory.allocUtf8String("libopenjdkjvmti.so"),i))return;let c=yt.v1_2|1073741824,a=t.tryGetEnvHandle(c);if(a===null)return;n=new Le(a,t);let l=Memory.alloc(8);l.writeU64(Et.canTagObjects),n.addCapabilities(l)!==0&&(n=null)}),n}function hc(t,e){J().flavor==="art"&&t.getClassName(e)}function _c(t){return{offset:v===4?{globalsLock:32,globals:72}:{globalsLock:64,globals:112}}}function mc(t){let e=t.vm,n=t.artRuntime,r=v===4?200:384,o=r+100*v,i=ee(),s=lo(),{isApiLevel34OrApexEquivalent:c}=t,a=null;for(let d=r;d!==o;d+=v)if(n.add(d).readPointer().equals(e)){let f,u=null;i>=33||s==="Tiramisu"||c?(f=[d-4*v],u=d-v):i>=30||s==="R"?(f=[d-3*v,d-4*v],u=d-v):i>=29?f=[d-2*v]:i>=27?f=[d-at-3*v]:f=[d-at-2*v];for(let _ of f){let h=_-v,g=h-v,b;c?b=g-9*v:i>=24?b=g-8*v:i>=23?b=g-7*v:b=g-4*v;let E={offset:{heap:b,threadList:g,internTable:h,classLinker:_,jniIdManager:u}};if(fo(n,E)!==null){a=E;break}}break}if(a===null)throw new Error("Unable to determine Runtime field offsets");let l=Bn()>=36e7;return a.offset.instrumentation=l?Sc(t):bc(t),a.offset.jniIdsIndirection=Tc(t),a}var gc={ia32:Kr,x64:Kr,arm:yc,arm64:Ec};function bc(t){let e=t["art::Runtime::DeoptimizeBootImage"];return e===void 0?null:xe(e,gc[Process.arch],{limit:30})}function Kr(t){if(t.mnemonic!=="lea")return null;let e=t.operands[1].value.disp;return e<256||e>1024?null:e}function yc(t){if(t.mnemonic!=="add.w")return null;let e=t.operands;if(e.length!==3)return null;let n=e[2];return n.type!=="imm"?null:n.value}function Ec(t){if(t.mnemonic!=="add")return null;let e=t.operands;if(e.length!==3||e[0].value==="sp"||e[1].value==="sp")return null;let n=e[2];if(n.type!=="imm")return null;let r=n.value.valueOf();return r<256||r>1024?null:r}var vc={ia32:Qr,x64:Qr,arm:wc,arm64:Ic};function Sc(t){let e=t["art::Runtime::DeoptimizeBootImage"];return e===void 0?null:xe(e,vc[Process.arch],{limit:30})}function Qr(t){if(t.mnemonic!=="mov")return null;let e=t.operands;if(e[0].value!=="rax")return null;let r=e[1];if(r.type!=="mem")return null;let o=r.value;if(o.base!=="rdi")return null;let i=o.disp;return i<256||i>1024?null:i}function wc(t){return null}function Ic(t){if(t.mnemonic!=="ldr")return null;let e=t.operands;if(e[0].value==="x0")return null;let n=e[1].value;if(n.base!=="x0")return null;let r=n.disp;return r<256||r>1024?null:r}var Cc={ia32:Yr,x64:Yr,arm:Ac,arm64:Lc};function Tc(t){let e=t.find("_ZN3art7Runtime12SetJniIdTypeENS_9JniIdTypeE");if(e===null)return null;let n=xe(e,Cc[Process.arch],{limit:20});if(n===null)throw new Error("Unable to determine Runtime.jni_ids_indirection_ offset");return n}function Yr(t){return t.mnemonic==="cmp"?t.operands[0].value.disp:null}function Ac(t){return t.mnemonic==="ldr.w"?t.operands[1].value.disp:null}function Lc(t,e){if(e===null)return null;let{mnemonic:n}=t,{mnemonic:r}=e;return n==="cmp"&&r==="ldr"||n==="bl"&&r==="str"?e.operands[1].value.disp:null}function xc(){let e={"4-21":136,"4-22":136,"4-23":172,"4-24":196,"4-25":196,"4-26":196,"4-27":196,"4-28":212,"4-29":172,"4-30":180,"4-31":180,"8-21":224,"8-22":224,"8-23":296,"8-24":344,"8-25":344,"8-26":352,"8-27":352,"8-28":392,"8-29":328,"8-30":336,"8-31":336}[`${v}-${ee()}`];if(e===void 0)throw new Error("Unable to determine Instrumentation field offsets");return{offset:{forcedInterpretOnly:4,deoptimizationEnabled:e}}}function Nc(t,e){let n=fo(t,e);if(n===null)throw new Error("Unable to determine ClassLinker field offsets");return n}function fo(t,e){if(vn!==null)return vn;let{classLinker:n,internTable:r}=e.offset,o=t.add(n).readPointer(),i=t.add(r).readPointer(),s=v===4?100:200,c=s+100*v,a=ee(),l=null;for(let d=s;d!==c;d+=v)if(o.add(d).readPointer().equals(i)){let f;a>=30||lo()==="R"?f=6:a>=29?f=4:a>=23?f=3:f=5;let u=d+f*v,_;a>=23?_=u-2*v:_=u-3*v,l={offset:{quickResolutionTrampoline:_,quickImtConflictTrampoline:u-v,quickGenericJniTrampoline:u,quickToInterpreterBridgeTrampoline:u+v}};break}return l!==null&&(vn=l),l}function Vn(t){let n=null;return t.perform(r=>{let o=Rt(t),i=_e(t),s={artArrayLengthSize:4,artArrayEntrySize:o.size,artArrayMax:50},c={artArrayLengthSize:v,artArrayEntrySize:i.size,artArrayMax:100},a=(f,u,_)=>{let h=f.add(u).readPointer();if(h.isNull())return null;let g=_===4?h.readU32():h.readU64().valueOf();return g<=0?null:{length:g,data:h.add(_)}},l=(f,u,_,h)=>{try{let g=a(f,u,h.artArrayLengthSize);if(g===null)return!1;let b=Math.min(g.length,h.artArrayMax);for(let E=0;E!==b;E++)if(g.data.add(E*h.artArrayEntrySize).equals(_))return!0}catch{}return!1},d=r.findClass("java/lang/Thread"),p=r.newGlobalRef(d);try{let f;ye(t,r,N=>{f=J()["art::JavaVMExt::DecodeGlobal"](t,N,p)});let u=to(r.getFieldId(p,"name","Ljava/lang/String;")),_=to(r.getStaticFieldId(p,"MAX_PRIORITY","I")),h=-1,g=-1;for(let N=0;N!==256;N+=4)h===-1&&l(f,N,_,s)&&(h=N),g===-1&&l(f,N,u,s)&&(g=N);if(g===-1||h===-1)throw new Error("Unable to find fields in java/lang/Thread; please file a bug");let b=g!==h?h:0,E=g,C=-1,A=Wn(r.getMethodId(p,"getName","()Ljava/lang/String;"));for(let N=0;N!==256;N+=4)C===-1&&l(f,N,A,c)&&(C=N);if(C===-1)throw new Error("Unable to find methods in java/lang/Thread; please file a bug");let M=-1,k=a(f,C,c.artArrayLengthSize).length;for(let N=C;N!==256;N+=4)if(f.add(N).readU16()===k){M=N;break}if(M===-1)throw new Error("Unable to find copied methods in java/lang/Thread; please file a bug");n={offset:{ifields:E,methods:C,sfields:b,copiedMethodsOffset:M}}}finally{r.deleteLocalRef(d),r.deleteGlobalRef(p)}}),n}function kc(t){let e=J(),n;return t.perform(r=>{let o=r.findClass("android/os/Process"),i=Wn(r.getStaticMethodId(o,"getElapsedCpuTime","()J"));r.deleteLocalRef(o);let s=Process.getModuleByName("libandroid_runtime.so"),c=s.base,a=c.add(s.size),l=ee(),d=l<=21?8:v,p=Ca|Ta|Aa|At,f=~(so|ja|Ma)>>>0,u=null,_=null,h=2;for(let E=0;E!==64&&h!==0;E+=4){let C=i.add(E);if(u===null){let A=C.readPointer();A.compare(c)>=0&&A.compare(a)<0&&(u=E,h--)}_===null&&(C.readU32()&f)===p&&(_=E,h--)}if(h!==0)throw new Error("Unable to determine ArtMethod field offsets");let g=u+d;n={size:l<=21?g+32:g+v,offset:{jniCode:u,quickCode:g,accessFlags:_}},"artInterpreterToCompiledCodeBridge"in e&&(n.offset.interpreterCode=u-d)}),n}function Rt(t){let e=ee();return e>=23?{size:16,offset:{accessFlags:4}}:e>=21?{size:24,offset:{accessFlags:12}}:null}function Mc(t){let e=ee(),n;return t.perform(r=>{let o=Pt(r),i=r.handle,s=null,c=null,a=null,l=null,d=null,p=null;for(let f=144;f!==256;f+=v)if(o.add(f).readPointer().equals(i)){c=f-6*v,d=f-4*v,p=f+2*v,e<=22&&(c-=v,s=c-v-9*8-3*4,a=f+6*v,d-=v,p-=v),l=f+9*v,e<=22&&(l+=2*v+4,v===8&&(l+=4)),e>=23&&(l+=v);break}if(l===null)throw new Error("Unable to determine ArtThread field offsets");n={offset:{isExceptionReportedToInstrumentation:s,exception:c,throwLocation:a,topHandleScope:l,managedStack:d,self:p}}}),n}function jc(){return ee()>=23?{offset:{topQuickFrame:0,link:v}}:{offset:{topQuickFrame:2*v,link:0}}}var Oc={ia32:Xr,x64:Xr,arm:Rc,arm64:Pc};function In(t,e){let n;return e.perform(r=>{let o=Pt(r),i=Oc[Process.arch],s=Instruction.parse(t),c=i(s);c!==null?n=o.add(c).readPointer():n=t}),n}function Xr(t){return t.mnemonic==="jmp"?t.operands[0].value.disp:null}function Rc(t){return t.mnemonic==="ldr.w"?t.operands[1].value.disp:null}function Pc(t){return t.mnemonic==="ldr"?t.operands[1].value.disp:null}function Pt(t){return t.handle.add(v).readPointer()}function Fc(){return Jn("ro.build.version.release")}function Dc(){return Jn("ro.build.version.codename")}function Uc(){return parseInt(Jn("ro.build.version.sdk"),10)}function Bc(){try{let t=File.readAllText("/proc/self/mountinfo"),e=null,n=new Map;for(let o of t.trimEnd().split(`
`)){let i=o.split(" "),s=i[4];if(!s.startsWith("/apex/com.android.art"))continue;let c=i[10];s.includes("@")?n.set(c,s.split("@")[1]):e=c}let r=n.get(e);return r!==void 0?parseInt(r):eo()}catch{return eo()}}function eo(){return ee()*1e7}var Cn=null,zc=92;function Jn(t){Cn===null&&(Cn=new NativeFunction(Process.getModuleByName("libc.so").getExportByName("__system_property_get"),"int",["pointer","pointer"],W));let e=Memory.alloc(zc);return Cn(Memory.allocUtf8String(t),e),e.readUtf8String()}function ye(t,e,n){let r=cc(t,e),o=Pt(e).toString();if(ot[o]=n,r(e.handle),ot[o]!==void 0)throw delete ot[o],new Error("Unable to perform state transition; please file a bug")}function Vc(t,e){let n=new NativeCallback(Jc,"void",["pointer"]);return mo(t,e,n)}function Jc(t){let e=t.toString(),n=ot[e];delete ot[e],n(t)}function Gn(t){let e=J(),n=e.artThreadList;e["art::ThreadList::SuspendAll"](n,Memory.allocUtf8String("frida"),!1?1:0);try{t()}finally{e["art::ThreadList::ResumeAll"](n)}}var Nn=class{constructor(e){let n=Memory.alloc(4*v),r=n.add(v);n.writePointer(r);let o=new NativeCallback((i,s)=>e(s)===!0?1:0,"bool",["pointer","pointer"]);r.add(2*v).writePointer(o),this.handle=n,this._onVisit=o}};function $n(t){return J()["art::ClassLinker::VisitClasses"]instanceof NativeFunction?new Nn(t):new NativeCallback(n=>t(n)===!0?1:0,"bool",["pointer","pointer"])}var kn=class{constructor(e){let n=Memory.alloc(4*v),r=n.add(v);n.writePointer(r);let o=new NativeCallback((i,s)=>{e(s)},"void",["pointer","pointer"]);r.add(2*v).writePointer(o),this.handle=n,this._onVisit=o}};function Hn(t){return new kn(t)}var Gc={"include-inlined-frames":0,"skip-inlined-frames":1},Mn=class{constructor(e,n,r,o=0,i=!0){let s=J(),c=512,a=3*v,l=Memory.alloc(c+a);s["art::StackVisitor::StackVisitor"](l,e,n,Gc[r],o,i?1:0);let d=l.add(c);l.writePointer(d);let p=new NativeCallback(this._visitFrame.bind(this),"bool",["pointer"]);d.add(2*v).writePointer(p),this.handle=l,this._onVisitFrame=p;let f=l.add(v===4?12:24);this._curShadowFrame=f,this._curQuickFrame=f.add(v),this._curQuickFramePc=f.add(2*v),this._curOatQuickMethodHeader=f.add(3*v),this._getMethodImpl=s["art::StackVisitor::GetMethod"],this._descLocImpl=s["art::StackVisitor::DescribeLocation"],this._getCQFIImpl=s["art::StackVisitor::GetCurrentQuickFrameInfo"]}walkStack(e=!1){J()["art::StackVisitor::WalkStack"](this.handle,e?1:0)}_visitFrame(){return this.visitFrame()?1:0}visitFrame(){throw new Error("Subclass must implement visitFrame")}getMethod(){let e=this._getMethodImpl(this.handle);return e.isNull()?null:new xt(e)}getCurrentQuickFramePc(){return this._curQuickFramePc.readPointer()}getCurrentQuickFrame(){return this._curQuickFrame.readPointer()}getCurrentShadowFrame(){return this._curShadowFrame.readPointer()}describeLocation(){let e=new Mt;return this._descLocImpl(e,this.handle),e.disposeToString()}getCurrentOatQuickMethodHeader(){return this._curOatQuickMethodHeader.readPointer()}getCurrentQuickFrameInfo(){return this._getCQFIImpl(this.handle)}},xt=class{constructor(e){this.handle=e}prettyMethod(e=!0){let n=new Mt;return J()["art::ArtMethod::PrettyMethod"](n,this.handle,e?1:0),n.disposeToString()}toString(){return`ArtMethod(handle=${this.handle})`}};function $c(t){return function(e){let n=Memory.alloc(12);return lc(t)(n,e),{frameSizeInBytes:n.readU32(),coreSpillMask:n.add(4).readU32(),fpSpillMask:n.add(8).readU32()}}}function Hc(t){let e=NULL;switch(Process.arch){case"ia32":e=He(32,n=>{n.putMovRegRegOffsetPtr("ecx","esp",4),n.putMovRegRegOffsetPtr("edx","esp",8),n.putCallAddressWithArguments(t,["ecx","edx"]),n.putMovRegReg("esp","ebp"),n.putPopReg("ebp"),n.putRet()});break;case"x64":e=He(32,n=>{n.putPushReg("rdi"),n.putCallAddressWithArguments(t,["rsi"]),n.putPopReg("rdi"),n.putMovRegPtrReg("rdi","rax"),n.putMovRegOffsetPtrReg("rdi",8,"edx"),n.putRet()});break;case"arm":e=He(16,n=>{n.putCallAddressWithArguments(t,["r0","r1"]),n.putPopRegs(["r0","lr"]),n.putMovRegReg("pc","lr")});break;case"arm64":e=He(64,n=>{n.putPushRegReg("x0","lr"),n.putCallAddressWithArguments(t,["x1"]),n.putPopRegReg("x2","lr"),n.putStrRegRegOffset("x0","x2",0),n.putStrRegRegOffset("w1","x2",8),n.putRet()});break}return new NativeFunction(e,"void",["pointer","pointer"],W)}var Zc={ia32:globalThis.X86Relocator,x64:globalThis.X86Relocator,arm:globalThis.ThumbRelocator,arm64:globalThis.Arm64Relocator},jn={ia32:globalThis.X86Writer,x64:globalThis.X86Writer,arm:globalThis.ThumbWriter,arm64:globalThis.Arm64Writer};function He(t,e){Sn===null&&(Sn=Memory.alloc(Process.pageSize));let n=Sn.add(Zr),r=Process.arch,o=jn[r];return Memory.patchCode(n,t,i=>{let s=new o(i,{pc:n});if(e(s),s.flush(),s.offset>t)throw new Error(`Wrote ${s.offset}, exceeding maximum of ${t}`)}),Zr+=t,r==="arm"?n.or(1):n}function qc(t,e){Kc(e),tl(e)}function Wc(t,e){let n=Ze(e).offset,r=ac().offset,o=`
#include <gum/guminterceptor.h>

extern GMutex lock;
extern GHashTable * methods;
extern GHashTable * replacements;
extern gpointer last_seen_art_method;

extern gpointer get_oat_quick_method_header_impl (gpointer method, gpointer pc);

void
init (void)
{
  g_mutex_init (&lock);
  methods = g_hash_table_new_full (NULL, NULL, NULL, NULL);
  replacements = g_hash_table_new_full (NULL, NULL, NULL, NULL);
}

void
finalize (void)
{
  g_hash_table_unref (replacements);
  g_hash_table_unref (methods);
  g_mutex_clear (&lock);
}

gboolean
is_replacement_method (gpointer method)
{
  gboolean is_replacement;

  g_mutex_lock (&lock);

  is_replacement = g_hash_table_contains (replacements, method);

  g_mutex_unlock (&lock);

  return is_replacement;
}

gpointer
get_replacement_method (gpointer original_method)
{
  gpointer replacement_method;

  g_mutex_lock (&lock);

  replacement_method = g_hash_table_lookup (methods, original_method);

  g_mutex_unlock (&lock);

  return replacement_method;
}

void
set_replacement_method (gpointer original_method,
                        gpointer replacement_method)
{
  g_mutex_lock (&lock);

  g_hash_table_insert (methods, original_method, replacement_method);
  g_hash_table_insert (replacements, replacement_method, original_method);

  g_mutex_unlock (&lock);
}

void
synchronize_replacement_methods (guint quick_code_offset,
                                 void * nterp_entrypoint,
                                 void * quick_to_interpreter_bridge)
{
  GHashTableIter iter;
  gpointer hooked_method, replacement_method;

  g_mutex_lock (&lock);

  g_hash_table_iter_init (&iter, methods);
  while (g_hash_table_iter_next (&iter, &hooked_method, &replacement_method))
  {
    void ** quick_code;

    *((uint32_t *) replacement_method) = *((uint32_t *) hooked_method);

    quick_code = hooked_method + quick_code_offset;
    if (*quick_code == nterp_entrypoint)
      *quick_code = quick_to_interpreter_bridge;
  }

  g_mutex_unlock (&lock);
}

void
delete_replacement_method (gpointer original_method)
{
  gpointer replacement_method;

  g_mutex_lock (&lock);

  replacement_method = g_hash_table_lookup (methods, original_method);
  if (replacement_method != NULL)
  {
    g_hash_table_remove (methods, original_method);
    g_hash_table_remove (replacements, replacement_method);
  }

  g_mutex_unlock (&lock);
}

gpointer
translate_method (gpointer method)
{
  gpointer translated_method;

  g_mutex_lock (&lock);

  translated_method = g_hash_table_lookup (replacements, method);

  g_mutex_unlock (&lock);

  return (translated_method != NULL) ? translated_method : method;
}

gpointer
find_replacement_method_from_quick_code (gpointer method,
                                         gpointer thread)
{
  gpointer replacement_method;
  gpointer managed_stack;
  gpointer top_quick_frame;
  gpointer link_managed_stack;
  gpointer * link_top_quick_frame;

  replacement_method = get_replacement_method (method);
  if (replacement_method == NULL)
    return NULL;

  /*
   * Stack check.
   *
   * Return NULL to indicate that the original method should be invoked, otherwise
   * return a pointer to the replacement ArtMethod.
   *
   * If the caller is our own JNI replacement stub, then a stack transition must
   * have been pushed onto the current thread's linked list.
   *
   * Therefore, we invoke the original method if the following conditions are met:
   *   1- The current managed stack is empty.
   *   2- The ArtMethod * inside the linked managed stack's top quick frame is the
   *      same as our replacement.
   */
  managed_stack = thread + ${n.managedStack};
  top_quick_frame = *((gpointer *) (managed_stack + ${r.topQuickFrame}));
  if (top_quick_frame != NULL)
    return replacement_method;

  link_managed_stack = *((gpointer *) (managed_stack + ${r.link}));
  if (link_managed_stack == NULL)
    return replacement_method;

  link_top_quick_frame = GSIZE_TO_POINTER (*((gsize *) (link_managed_stack + ${r.topQuickFrame})) & ~((gsize) 1));
  if (link_top_quick_frame == NULL || *link_top_quick_frame != replacement_method)
    return replacement_method;

  return NULL;
}

void
on_interpreter_do_call (GumInvocationContext * ic)
{
  gpointer method, replacement_method;

  method = gum_invocation_context_get_nth_argument (ic, 0);

  replacement_method = get_replacement_method (method);
  if (replacement_method != NULL)
    gum_invocation_context_replace_nth_argument (ic, 0, replacement_method);
}

gpointer
on_art_method_get_oat_quick_method_header (gpointer method,
                                           gpointer pc)
{
  if (is_replacement_method (method))
    return NULL;

  return get_oat_quick_method_header_impl (method, pc);
}

void
on_art_method_pretty_method (GumInvocationContext * ic)
{
  const guint this_arg_index = ${Process.arch==="arm64"?0:1};
  gpointer method;

  method = gum_invocation_context_get_nth_argument (ic, this_arg_index);
  if (method == NULL)
    gum_invocation_context_replace_nth_argument (ic, this_arg_index, last_seen_art_method);
  else
    last_seen_art_method = method;
}

void
on_leave_gc_concurrent_copying_copying_phase (GumInvocationContext * ic)
{
  GHashTableIter iter;
  gpointer hooked_method, replacement_method;

  g_mutex_lock (&lock);

  g_hash_table_iter_init (&iter, methods);
  while (g_hash_table_iter_next (&iter, &hooked_method, &replacement_method))
    *((uint32_t *) replacement_method) = *((uint32_t *) hooked_method);

  g_mutex_unlock (&lock);
}
`,i=8,s=v,c=v,a=v,d=Memory.alloc(i+s+c+a),p=d.add(i),f=p.add(s),u=f.add(c),_=t.find(v===4?"_ZN3art9ArtMethod23GetOatQuickMethodHeaderEj":"_ZN3art9ArtMethod23GetOatQuickMethodHeaderEm"),h=new CModule(o,{lock:d,methods:p,replacements:f,last_seen_art_method:u,get_oat_quick_method_header_impl:_??ptr("0xdeadbeef")}),g={exceptions:"propagate",scheduling:"exclusive"};return{handle:h,replacedMethods:{isReplacement:new NativeFunction(h.is_replacement_method,"bool",["pointer"],g),get:new NativeFunction(h.get_replacement_method,"pointer",["pointer"],g),set:new NativeFunction(h.set_replacement_method,"void",["pointer","pointer"],g),synchronize:new NativeFunction(h.synchronize_replacement_methods,"void",["uint","pointer","pointer"],g),delete:new NativeFunction(h.delete_replacement_method,"void",["pointer"],g),translate:new NativeFunction(h.translate_method,"pointer",["pointer"],g),findReplacementFromQuickCode:h.find_replacement_method_from_quick_code},getOatQuickMethodHeaderImpl:_,hooks:{Interpreter:{doCall:h.on_interpreter_do_call},ArtMethod:{getOatQuickMethodHeader:h.on_art_method_get_oat_quick_method_header,prettyMethod:h.on_art_method_pretty_method},Gc:{copyingPhase:{onLeave:h.on_leave_gc_concurrent_copying_copying_phase},runFlip:{onEnter:h.on_leave_gc_concurrent_copying_copying_phase}}}}}function Kc(t){Wr||(Wr=!0,Qc(t),Yc(),Xc(),el())}function Qc(t){let e=J();[e.artQuickGenericJniTrampoline,e.artQuickToInterpreterBridge,e.artQuickResolutionTrampoline].forEach(r=>{Memory.protect(r,32,"rwx");let o=new kt(r);o.activate(t),po.push(o)})}function Yc(){let t=J(),e=ee(),{isApiLevel34OrApexEquivalent:n}=t,r;if(e<=22)r=/^_ZN3art11interpreter6DoCallILb[0-1]ELb[0-1]EEEbPNS_6mirror9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtPNS_6JValueE$/;else if(e<=33&&!n)r=/^_ZN3art11interpreter6DoCallILb[0-1]ELb[0-1]EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtPNS_6JValueE$/;else if(n)r=/^_ZN3art11interpreter6DoCallILb[0-1]EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtbPNS_6JValueE$/;else throw new Error("Unable to find method invocation in ART; please file a bug");let o=t.module,i=[...o.enumerateExports(),...o.enumerateSymbols()].filter(s=>r.test(s.name));if(i.length===0)throw new Error("Unable to find method invocation in ART; please file a bug");for(let s of i)Interceptor.attach(s.address,oe.hooks.Interpreter.doCall)}function Xc(){let t=J(),n=t.module.findSymbolByName("_ZN3art2gc4Heap22CollectGarbageInternalENS0_9collector6GcTypeENS0_7GcCauseEbj");if(n===null)return;let{artNterpEntryPoint:r,artQuickToInterpreterBridge:o}=t,i=_e(t.vm).offset.quickCode;Interceptor.attach(n,{onLeave(){oe.replacedMethods.synchronize(i,r,o)}})}function el(){let t=[["_ZN3art11ClassLinker26VisiblyInitializedCallback22MarkVisiblyInitializedEPNS_6ThreadE","e90340f8 : ff0ff0ff"],["_ZN3art11ClassLinker26VisiblyInitializedCallback29AdjustThreadVisibilityCounterEPNS_6ThreadEl","7f0f00f9 : 1ffcffff"]],e=J(),n=e.module;for(let[r,o]of t){let i=n.findSymbolByName(r);if(i===null)continue;let s=Memory.scanSync(i,8192,o);if(s.length===0)return;let{artNterpEntryPoint:c,artQuickToInterpreterBridge:a}=e,l=_e(e.vm).offset.quickCode;Interceptor.attach(s[0].address,function(){oe.replacedMethods.synchronize(l,c,a)});return}}function tl(t){if(qr)return;if(qr=!0,!rl()){let{getOatQuickMethodHeaderImpl:i}=oe;if(i===null)return;try{Interceptor.replace(i,oe.hooks.ArtMethod.getOatQuickMethodHeader)}catch{}}let e=ee(),n=null,r=J();e>28?n=r.find("_ZN3art2gc9collector17ConcurrentCopying12CopyingPhaseEv"):e>22&&(n=r.find("_ZN3art2gc9collector17ConcurrentCopying12MarkingPhaseEv")),n!==null&&Interceptor.attach(n,oe.hooks.Gc.copyingPhase);let o=null;o=r.find("_ZN3art6Thread15RunFlipFunctionEPS0_"),o===null&&(o=r.find("_ZN3art6Thread15RunFlipFunctionEPS0_b")),o!==null&&Interceptor.attach(o,oe.hooks.Gc.runFlip)}var nl={arm:{signatures:[{pattern:["b0 68","01 30","0c d0","1b 98",":","c0 ff","c0 ff","00 ff","00 2f"],validateMatch:Tn},{pattern:["d8 f8 08 00","01 30","0c d0","1b 98",":","f0 ff ff 0f","ff ff","00 ff","00 2f"],validateMatch:Tn},{pattern:["b0 68","01 30","40 f0 c3 80","00 25",":","c0 ff","c0 ff","c0 fb 00 d0","ff f8"],validateMatch:Tn}],instrument:il},arm64:{signatures:[{pattern:["0a 40 b9","1f 05 00 31","40 01 00 54","88 39 00 f0",":","fc ff ff","1f fc ff ff","1f 00 00 ff","00 00 00 9f"],offset:1,validateMatch:An},{pattern:["0a 40 b9","1f 05 00 31","40 01 00 54","00 0e 40 f9",":","fc ff ff","1f fc ff ff","1f 00 00 ff","00 fc ff ff"],offset:1,validateMatch:An},{pattern:["0a 40 b9","1f 05 00 31","01 34 00 54","e0 03 1f aa",":","fc ff ff","1f fc ff ff","1f 00 00 ff","e0 ff ff ff"],offset:1,validateMatch:An}],instrument:sl}};function Tn({address:t,size:e}){let n=Instruction.parse(t.or(1)),[r,o]=n.operands,i=o.value.base,s=r.value,c=Instruction.parse(n.next.add(2)),a=ptr(c.operands[0].value),l=c.address.add(c.size),d,p;return c.mnemonic==="beq"?(d=l,p=a):(d=a,p=l),xe(d.or(1),f,{limit:3});function f(u){let{mnemonic:_}=u;if(!(_==="ldr"||_==="ldr.w"))return null;let{base:h,disp:g}=u.operands[1].value;return h===i&&g===20?{methodReg:i,scratchReg:s,target:{whenTrue:a,whenRegularMethod:d,whenRuntimeMethod:p}}:null}}function An({address:t,size:e}){let[n,r]=Instruction.parse(t).operands,o=r.value.base,i="x"+n.value.substring(1),s=Instruction.parse(t.add(8)),c=ptr(s.operands[0].value),a=t.add(12),l,d;return s.mnemonic==="b.eq"?(l=a,d=c):(l=c,d=a),xe(l,p,{limit:3});function p(f){if(f.mnemonic!=="ldr")return null;let{base:u,disp:_}=f.operands[1].value;return u===o&&_===24?{methodReg:o,scratchReg:i,target:{whenTrue:c,whenRegularMethod:l,whenRuntimeMethod:d}}:null}}function rl(){if(ee()<31)return!1;let t=nl[Process.arch];if(t===void 0)return!1;let e=t.signatures.map(({pattern:r,offset:o=0,validateMatch:i=ol})=>({pattern:new MatchPattern(r.join("")),offset:o,validateMatch:i})),n=[];for(let{base:r,size:o}of J().module.enumerateRanges("--x"))for(let{pattern:i,offset:s,validateMatch:c}of e){let a=Memory.scanSync(r,o,i).map(({address:l,size:d})=>({address:l.sub(s),size:d+s})).filter(l=>{let d=c(l);return d===null?!1:(l.validationResult=d,!0)});n.push(...a)}return n.length===0?!1:(n.forEach(t.instrument),!0)}function ol(){return{}}var Nt=class{constructor(e,n,r){this.address=e,this.size=n,this.originalCode=e.readByteArray(n),this.trampoline=r}revert(){Memory.patchCode(this.address,this.size,e=>{e.writeByteArray(this.originalCode)})}};function il({address:t,size:e,validationResult:n}){let{methodReg:r,target:o}=n,i=Memory.alloc(Process.pageSize),s=e;Memory.patchCode(i,256,c=>{let a=new ThumbWriter(c,{pc:i}),l=new ThumbRelocator(t,a);for(let _=0;_!==2;_++)l.readOne();l.writeAll(),l.readOne(),l.skipOne(),a.putBCondLabel("eq","runtime_or_replacement_method");let d=[45,237,16,10];a.putBytes(d);let p=["r0","r1","r2","r3"];a.putPushRegs(p),a.putCallAddressWithArguments(oe.replacedMethods.isReplacement,[r]),a.putCmpRegImm("r0",0),a.putPopRegs(p);let f=[189,236,16,10];a.putBytes(f),a.putBCondLabel("ne","runtime_or_replacement_method"),a.putBLabel("regular_method"),l.readOne();let u=l.input.address.equals(o.whenRegularMethod);for(a.putLabel(u?"regular_method":"runtime_or_replacement_method"),l.writeOne();s<10;){let _=l.readOne();if(_===0){s=10;break}s=_}l.writeAll(),a.putBranchAddress(t.add(s+1)),a.putLabel(u?"runtime_or_replacement_method":"regular_method"),a.putBranchAddress(o.whenTrue),a.flush()}),zn.push(new Nt(t,s,i)),Memory.patchCode(t,s,c=>{let a=new ThumbWriter(c,{pc:t});a.putLdrRegAddress("pc",i.or(1)),a.flush()})}function sl({address:t,size:e,validationResult:n}){let{methodReg:r,scratchReg:o,target:i}=n,s=Memory.alloc(Process.pageSize);Memory.patchCode(s,256,c=>{let a=new Arm64Writer(c,{pc:s}),l=new Arm64Relocator(t,a);for(let _=0;_!==2;_++)l.readOne();l.writeAll(),l.readOne(),l.skipOne(),a.putBCondLabel("eq","runtime_or_replacement_method");let d=["d0","d1","d2","d3","d4","d5","d6","d7","x0","x1","x2","x3","x4","x5","x6","x7","x8","x9","x10","x11","x12","x13","x14","x15","x16","x17"],p=d.length;for(let _=0;_!==p;_+=2)a.putPushRegReg(d[_],d[_+1]);a.putCallAddressWithArguments(oe.replacedMethods.isReplacement,[r]),a.putCmpRegReg("x0","xzr");for(let _=p-2;_>=0;_-=2)a.putPopRegReg(d[_],d[_+1]);a.putBCondLabel("ne","runtime_or_replacement_method"),a.putBLabel("regular_method"),l.readOne();let f=l.input,u=f.address.equals(i.whenRegularMethod);a.putLabel(u?"regular_method":"runtime_or_replacement_method"),l.writeOne(),a.putBranchAddress(f.next),a.putLabel(u?"runtime_or_replacement_method":"regular_method"),a.putBranchAddress(i.whenTrue),a.flush()}),zn.push(new Nt(t,e,s)),Memory.patchCode(t,e,c=>{let a=new Arm64Writer(c,{pc:t});a.putLdrRegAddress(o,s),a.putBrReg(o),a.flush()})}function al(t){return new uo(t)}function cl(t){return oe.replacedMethods.translate(t)}function Zn(t,e={}){let{limit:n=16}=e,r=t.getEnv();return it===null&&(it=ll(t,r)),it.backtrace(r,n)}function ll(t,e){let n=J(),r=Memory.alloc(Process.pointerSize),o=new CModule(`
#include <glib.h>
#include <stdbool.h>
#include <string.h>
#include <gum/gumtls.h>
#include <json-glib/json-glib.h>

typedef struct _ArtBacktrace ArtBacktrace;
typedef struct _ArtStackFrame ArtStackFrame;

typedef struct _ArtStackVisitor ArtStackVisitor;
typedef struct _ArtStackVisitorVTable ArtStackVisitorVTable;

typedef struct _ArtClass ArtClass;
typedef struct _ArtMethod ArtMethod;
typedef struct _ArtThread ArtThread;
typedef struct _ArtContext ArtContext;

typedef struct _JNIEnv JNIEnv;

typedef struct _StdString StdString;
typedef struct _StdTinyString StdTinyString;
typedef struct _StdLargeString StdLargeString;

typedef enum {
  STACK_WALK_INCLUDE_INLINED_FRAMES,
  STACK_WALK_SKIP_INLINED_FRAMES,
} StackWalkKind;

struct _StdTinyString
{
  guint8 unused;
  gchar data[(3 * sizeof (gpointer)) - 1];
};

struct _StdLargeString
{
  gsize capacity;
  gsize size;
  gchar * data;
};

struct _StdString
{
  union
  {
    guint8 flags;
    StdTinyString tiny;
    StdLargeString large;
  };
};

struct _ArtBacktrace
{
  GChecksum * id;
  GArray * frames;
  gchar * frames_json;
};

struct _ArtStackFrame
{
  ArtMethod * method;
  gsize dexpc;
  StdString description;
};

struct _ArtStackVisitorVTable
{
  void (* unused1) (void);
  void (* unused2) (void);
  bool (* visit) (ArtStackVisitor * visitor);
};

struct _ArtStackVisitor
{
  ArtStackVisitorVTable * vtable;

  guint8 padding[512];

  ArtStackVisitorVTable vtable_storage;

  ArtBacktrace * backtrace;
};

struct _ArtMethod
{
  guint32 declaring_class;
  guint32 access_flags;
};

extern GumTlsKey current_backtrace;

extern void (* perform_art_thread_state_transition) (JNIEnv * env);

extern ArtContext * art_make_context (ArtThread * thread);

extern void art_stack_visitor_init (ArtStackVisitor * visitor, ArtThread * thread, void * context, StackWalkKind walk_kind,
    size_t num_frames, bool check_suspended);
extern void art_stack_visitor_walk_stack (ArtStackVisitor * visitor, bool include_transitions);
extern ArtMethod * art_stack_visitor_get_method (ArtStackVisitor * visitor);
extern void art_stack_visitor_describe_location (StdString * description, ArtStackVisitor * visitor);
extern ArtMethod * translate_method (ArtMethod * method);
extern void translate_location (ArtMethod * method, guint32 pc, const gchar ** source_file, gint32 * line_number);
extern void get_class_location (StdString * result, ArtClass * klass);
extern void cxx_delete (void * mem);
extern unsigned long strtoul (const char * str, char ** endptr, int base);

static bool visit_frame (ArtStackVisitor * visitor);
static void art_stack_frame_destroy (ArtStackFrame * frame);

static void append_jni_type_name (GString * s, const gchar * name, gsize length);

static void std_string_destroy (StdString * str);
static gchar * std_string_get_data (StdString * str);

void
init (void)
{
  current_backtrace = gum_tls_key_new ();
}

void
finalize (void)
{
  gum_tls_key_free (current_backtrace);
}

ArtBacktrace *
_create (JNIEnv * env,
         guint limit)
{
  ArtBacktrace * bt;

  bt = g_new (ArtBacktrace, 1);
  bt->id = g_checksum_new (G_CHECKSUM_SHA1);
  bt->frames = (limit != 0)
      ? g_array_sized_new (FALSE, FALSE, sizeof (ArtStackFrame), limit)
      : g_array_new (FALSE, FALSE, sizeof (ArtStackFrame));
  g_array_set_clear_func (bt->frames, (GDestroyNotify) art_stack_frame_destroy);
  bt->frames_json = NULL;

  gum_tls_key_set_value (current_backtrace, bt);

  perform_art_thread_state_transition (env);

  gum_tls_key_set_value (current_backtrace, NULL);

  return bt;
}

void
_on_thread_state_transition_complete (ArtThread * thread)
{
  ArtContext * context;
  ArtStackVisitor visitor = {
    .vtable_storage = {
      .visit = visit_frame,
    },
  };

  context = art_make_context (thread);

  art_stack_visitor_init (&visitor, thread, context, STACK_WALK_SKIP_INLINED_FRAMES, 0, true);
  visitor.vtable = &visitor.vtable_storage;
  visitor.backtrace = gum_tls_key_get_value (current_backtrace);

  art_stack_visitor_walk_stack (&visitor, false);

  cxx_delete (context);
}

static bool
visit_frame (ArtStackVisitor * visitor)
{
  ArtBacktrace * bt = visitor->backtrace;
  ArtStackFrame frame;
  const gchar * description, * dexpc_part;

  frame.method = art_stack_visitor_get_method (visitor);

  art_stack_visitor_describe_location (&frame.description, visitor);

  description = std_string_get_data (&frame.description);
  if (strstr (description, " '<") != NULL)
    goto skip;

  dexpc_part = strstr (description, " at dex PC 0x");
  if (dexpc_part == NULL)
    goto skip;
  frame.dexpc = strtoul (dexpc_part + 13, NULL, 16);

  g_array_append_val (bt->frames, frame);

  g_checksum_update (bt->id, (guchar *) &frame.method, sizeof (frame.method));
  g_checksum_update (bt->id, (guchar *) &frame.dexpc, sizeof (frame.dexpc));

  return true;

skip:
  std_string_destroy (&frame.description);
  return true;
}

static void
art_stack_frame_destroy (ArtStackFrame * frame)
{
  std_string_destroy (&frame->description);
}

void
_destroy (ArtBacktrace * backtrace)
{
  g_free (backtrace->frames_json);
  g_array_free (backtrace->frames, TRUE);
  g_checksum_free (backtrace->id);
  g_free (backtrace);
}

const gchar *
_get_id (ArtBacktrace * backtrace)
{
  return g_checksum_get_string (backtrace->id);
}

const gchar *
_get_frames (ArtBacktrace * backtrace)
{
  GArray * frames = backtrace->frames;
  JsonBuilder * b;
  guint i;
  JsonNode * root;

  if (backtrace->frames_json != NULL)
    return backtrace->frames_json;

  b = json_builder_new_immutable ();

  json_builder_begin_array (b);

  for (i = 0; i != frames->len; i++)
  {
    ArtStackFrame * frame = &g_array_index (frames, ArtStackFrame, i);
    gchar * description, * ret_type, * paren_open, * paren_close, * arg_types, * token, * method_name, * class_name;
    GString * signature;
    gchar * cursor;
    ArtMethod * translated_method;
    StdString location;
    gsize dexpc;
    const gchar * source_file;
    gint32 line_number;

    description = std_string_get_data (&frame->description);

    ret_type = strchr (description, '\\'') + 1;

    paren_open = strchr (ret_type, '(');
    paren_close = strchr (paren_open, ')');
    *paren_open = '\\0';
    *paren_close = '\\0';

    arg_types = paren_open + 1;

    token = strrchr (ret_type, '.');
    *token = '\\0';

    method_name = token + 1;

    token = strrchr (ret_type, ' ');
    *token = '\\0';

    class_name = token + 1;

    signature = g_string_sized_new (128);

    append_jni_type_name (signature, class_name, method_name - class_name - 1);
    g_string_append_c (signature, ',');
    g_string_append (signature, method_name);
    g_string_append (signature, ",(");

    if (arg_types != paren_close)
    {
      for (cursor = arg_types; cursor != NULL;)
      {
        gsize length;
        gchar * next;

        token = strstr (cursor, ", ");
        if (token != NULL)
        {
          length = token - cursor;
          next = token + 2;
        }
        else
        {
          length = paren_close - cursor;
          next = NULL;
        }

        append_jni_type_name (signature, cursor, length);

        cursor = next;
      }
    }

    g_string_append_c (signature, ')');

    append_jni_type_name (signature, ret_type, class_name - ret_type - 1);

    translated_method = translate_method (frame->method);
    dexpc = (translated_method == frame->method) ? frame->dexpc : 0;

    get_class_location (&location, GSIZE_TO_POINTER (translated_method->declaring_class));

    translate_location (translated_method, dexpc, &source_file, &line_number);

    json_builder_begin_object (b);

    json_builder_set_member_name (b, "signature");
    json_builder_add_string_value (b, signature->str);

    json_builder_set_member_name (b, "origin");
    json_builder_add_string_value (b, std_string_get_data (&location));

    json_builder_set_member_name (b, "className");
    json_builder_add_string_value (b, class_name);

    json_builder_set_member_name (b, "methodName");
    json_builder_add_string_value (b, method_name);

    json_builder_set_member_name (b, "methodFlags");
    json_builder_add_int_value (b, translated_method->access_flags);

    json_builder_set_member_name (b, "fileName");
    json_builder_add_string_value (b, source_file);

    json_builder_set_member_name (b, "lineNumber");
    json_builder_add_int_value (b, line_number);

    json_builder_end_object (b);

    std_string_destroy (&location);
    g_string_free (signature, TRUE);
  }

  json_builder_end_array (b);

  root = json_builder_get_root (b);
  backtrace->frames_json = json_to_string (root, FALSE);
  json_node_unref (root);

  return backtrace->frames_json;
}

static void
append_jni_type_name (GString * s,
                      const gchar * name,
                      gsize length)
{
  gchar shorty = '\\0';
  gsize i;

  switch (name[0])
  {
    case 'b':
      if (strncmp (name, "boolean", length) == 0)
        shorty = 'Z';
      else if (strncmp (name, "byte", length) == 0)
        shorty = 'B';
      break;
    case 'c':
      if (strncmp (name, "char", length) == 0)
        shorty = 'C';
      break;
    case 'd':
      if (strncmp (name, "double", length) == 0)
        shorty = 'D';
      break;
    case 'f':
      if (strncmp (name, "float", length) == 0)
        shorty = 'F';
      break;
    case 'i':
      if (strncmp (name, "int", length) == 0)
        shorty = 'I';
      break;
    case 'l':
      if (strncmp (name, "long", length) == 0)
        shorty = 'J';
      break;
    case 's':
      if (strncmp (name, "short", length) == 0)
        shorty = 'S';
      break;
    case 'v':
      if (strncmp (name, "void", length) == 0)
        shorty = 'V';
      break;
  }

  if (shorty != '\\0')
  {
    g_string_append_c (s, shorty);

    return;
  }

  if (length > 2 && name[length - 2] == '[' && name[length - 1] == ']')
  {
    g_string_append_c (s, '[');
    append_jni_type_name (s, name, length - 2);

    return;
  }

  g_string_append_c (s, 'L');

  for (i = 0; i != length; i++)
  {
    gchar ch = name[i];
    if (ch != '.')
      g_string_append_c (s, ch);
    else
      g_string_append_c (s, '/');
  }

  g_string_append_c (s, ';');
}

static void
std_string_destroy (StdString * str)
{
  bool is_large = (str->flags & 1) != 0;
  if (is_large)
    cxx_delete (str->large.data);
}

static gchar *
std_string_get_data (StdString * str)
{
  bool is_large = (str->flags & 1) != 0;
  return is_large ? str->large.data : str->tiny.data;
}
`,{current_backtrace:Memory.alloc(Process.pointerSize),perform_art_thread_state_transition:r,art_make_context:n["art::Thread::GetLongJumpContext"]??n["art::Context::Create"],art_stack_visitor_init:n["art::StackVisitor::StackVisitor"],art_stack_visitor_walk_stack:n["art::StackVisitor::WalkStack"],art_stack_visitor_get_method:n["art::StackVisitor::GetMethod"],art_stack_visitor_describe_location:n["art::StackVisitor::DescribeLocation"],translate_method:oe.replacedMethods.translate,translate_location:n["art::Monitor::TranslateLocation"],get_class_location:n["art::mirror::Class::GetLocation"],cxx_delete:n.$delete,strtoul:Process.getModuleByName("libc.so").getExportByName("strtoul")}),i=new NativeFunction(o._create,"pointer",["pointer","uint"],W),s=new NativeFunction(o._destroy,"void",["pointer"],W),c={exceptions:"propagate",scheduling:"exclusive"},a=new NativeFunction(o._get_id,"pointer",["pointer"],c),l=new NativeFunction(o._get_frames,"pointer",["pointer"],c),d=mo(t,e,o._on_thread_state_transition_complete);o._performData=d,r.writePointer(d),o.backtrace=(f,u)=>{let _=i(f,u),h=new On(_);return Script.bindWeak(h,p.bind(null,_)),h};function p(f){s(f)}return o.getId=f=>a(f).readUtf8String(),o.getFrames=f=>JSON.parse(l(f).readUtf8String()),o}var On=class{constructor(e){this.handle=e}get id(){return it.getId(this.handle)}get frames(){return it.getFrames(this.handle)}};function qn(){Lt.forEach(t=>{t.vtablePtr.writePointer(t.vtable),t.vtableCountPtr.writeS32(t.vtableCount)}),Lt.clear();for(let t of po.splice(0))t.deactivate();for(let t of zn.splice(0))t.revert()}function Wn(t){return ho(t,"art::jni::JniIdManager::DecodeMethodId")}function to(t){return ho(t,"art::jni::JniIdManager::DecodeFieldId")}function ho(t,e){let n=J(),r=co(n).offset,o=r.jniIdManager,i=r.jniIdsIndirection;if(o!==null&&i!==null){let s=n.artRuntime;if(s.add(i).readInt()!==Ra){let a=s.add(o).readPointer();return n[e](a,t)}}return t}var dl={ia32:ul,x64:pl,arm:fl,arm64:hl};function ul(t,e,n,r,o){let i=Ze(o).offset,s=_e(o).offset,c;return Memory.patchCode(t,128,a=>{let l=new X86Writer(a,{pc:t}),d=new X86Relocator(e,l),p=[15,174,4,36],f=[15,174,12,36];l.putPushax(),l.putMovRegReg("ebp","esp"),l.putAndRegU32("esp",4294967280),l.putSubRegImm("esp",512),l.putBytes(p),l.putMovRegFsU32Ptr("ebx",i.self),l.putCallAddressWithAlignedArguments(oe.replacedMethods.findReplacementFromQuickCode,["eax","ebx"]),l.putTestRegReg("eax","eax"),l.putJccShortLabel("je","restore_registers","no-hint"),l.putMovRegOffsetPtrReg("ebp",7*4,"eax"),l.putLabel("restore_registers"),l.putBytes(f),l.putMovRegReg("esp","ebp"),l.putPopax(),l.putJccShortLabel("jne","invoke_replacement","no-hint");do c=d.readOne();while(c<n&&!d.eoi);d.writeAll(),d.eoi||l.putJmpAddress(e.add(c)),l.putLabel("invoke_replacement"),l.putJmpRegOffsetPtr("eax",s.quickCode),l.flush()}),c}function pl(t,e,n,r,o){let i=Ze(o).offset,s=_e(o).offset,c;return Memory.patchCode(t,256,a=>{let l=new X86Writer(a,{pc:t}),d=new X86Relocator(e,l),p=[15,174,4,36],f=[15,174,12,36];l.putPushax(),l.putMovRegReg("rbp","rsp"),l.putAndRegU32("rsp",4294967280),l.putSubRegImm("rsp",512),l.putBytes(p),l.putMovRegGsU32Ptr("rbx",i.self),l.putCallAddressWithAlignedArguments(oe.replacedMethods.findReplacementFromQuickCode,["rdi","rbx"]),l.putTestRegReg("rax","rax"),l.putJccShortLabel("je","restore_registers","no-hint"),l.putMovRegOffsetPtrReg("rbp",8*8,"rax"),l.putLabel("restore_registers"),l.putBytes(f),l.putMovRegReg("rsp","rbp"),l.putPopax(),l.putJccShortLabel("jne","invoke_replacement","no-hint");do c=d.readOne();while(c<n&&!d.eoi);d.writeAll(),d.eoi||l.putJmpAddress(e.add(c)),l.putLabel("invoke_replacement"),l.putJmpRegOffsetPtr("rdi",s.quickCode),l.flush()}),c}function fl(t,e,n,r,o){let i=_e(o).offset,s=e.and(Un),c;return Memory.patchCode(t,128,a=>{let l=new ThumbWriter(a,{pc:t}),d=new ThumbRelocator(s,l),p=[45,237,16,10],f=[189,236,16,10];l.putPushRegs(["r1","r2","r3","r5","r6","r7","r8","r10","r11","lr"]),l.putBytes(p),l.putSubRegRegImm("sp","sp",8),l.putStrRegRegOffset("r0","sp",0),l.putCallAddressWithArguments(oe.replacedMethods.findReplacementFromQuickCode,["r0","r9"]),l.putCmpRegImm("r0",0),l.putBCondLabel("eq","restore_registers"),l.putStrRegRegOffset("r0","sp",0),l.putLabel("restore_registers"),l.putLdrRegRegOffset("r0","sp",0),l.putAddRegRegImm("sp","sp",8),l.putBytes(f),l.putPopRegs(["lr","r11","r10","r8","r7","r6","r5","r3","r2","r1"]),l.putBCondLabel("ne","invoke_replacement");do c=d.readOne();while(c<n&&!d.eoi);d.writeAll(),d.eoi||l.putLdrRegAddress("pc",e.add(c)),l.putLabel("invoke_replacement"),l.putLdrRegRegOffset("pc","r0",i.quickCode),l.flush()}),c}function hl(t,e,n,{availableScratchRegs:r},o){let i=_e(o).offset,s;return Memory.patchCode(t,256,c=>{let a=new Arm64Writer(c,{pc:t}),l=new Arm64Relocator(e,a);a.putPushRegReg("d0","d1"),a.putPushRegReg("d2","d3"),a.putPushRegReg("d4","d5"),a.putPushRegReg("d6","d7"),a.putPushRegReg("x1","x2"),a.putPushRegReg("x3","x4"),a.putPushRegReg("x5","x6"),a.putPushRegReg("x7","x20"),a.putPushRegReg("x21","x22"),a.putPushRegReg("x23","x24"),a.putPushRegReg("x25","x26"),a.putPushRegReg("x27","x28"),a.putPushRegReg("x29","lr"),a.putSubRegRegImm("sp","sp",16),a.putStrRegRegOffset("x0","sp",0),a.putCallAddressWithArguments(oe.replacedMethods.findReplacementFromQuickCode,["x0","x19"]),a.putCmpRegReg("x0","xzr"),a.putBCondLabel("eq","restore_registers"),a.putStrRegRegOffset("x0","sp",0),a.putLabel("restore_registers"),a.putLdrRegRegOffset("x0","sp",0),a.putAddRegRegImm("sp","sp",16),a.putPopRegReg("x29","lr"),a.putPopRegReg("x27","x28"),a.putPopRegReg("x25","x26"),a.putPopRegReg("x23","x24"),a.putPopRegReg("x21","x22"),a.putPopRegReg("x7","x20"),a.putPopRegReg("x5","x6"),a.putPopRegReg("x3","x4"),a.putPopRegReg("x1","x2"),a.putPopRegReg("d6","d7"),a.putPopRegReg("d4","d5"),a.putPopRegReg("d2","d3"),a.putPopRegReg("d0","d1"),a.putBCondLabel("ne","invoke_replacement");do s=l.readOne();while(s<n&&!l.eoi);if(l.writeAll(),!l.eoi){let d=Array.from(r)[0];a.putLdrRegAddress(d,e.add(s)),a.putBrReg(d)}a.putLabel("invoke_replacement"),a.putLdrRegRegOffset("x16","x0",i.quickCode),a.putBrReg("x16"),a.flush()}),s}var _l={ia32:no,x64:no,arm:ml,arm64:gl};function no(t,e,n){Memory.patchCode(t,16,r=>{let o=new X86Writer(r,{pc:t});o.putJmpAddress(e),o.flush()})}function ml(t,e,n){let r=t.and(Un);Memory.patchCode(r,16,o=>{let i=new ThumbWriter(o,{pc:r});i.putLdrRegAddress("pc",e.or(1)),i.flush()})}function gl(t,e,n){Memory.patchCode(t,16,r=>{let o=new Arm64Writer(r,{pc:t});n===16?o.putLdrRegAddress("x16",e):o.putAdrpRegAddress("x16",e),o.putBrReg("x16"),o.flush()})}var bl={ia32:5,x64:16,arm:8,arm64:16},kt=class{constructor(e){this.quickCode=e,this.quickCodeAddress=Process.arch==="arm"?e.and(Un):e,this.redirectSize=0,this.trampoline=null,this.overwrittenPrologue=null,this.overwrittenPrologueLength=0}_canRelocateCode(e,n){let r=jn[Process.arch],o=Zc[Process.arch],{quickCodeAddress:i}=this,s=new r(i),c=new o(i,s),a;if(Process.arch==="arm64"){let l=new Set(["x16","x17"]);do{let d=c.readOne(),p=new Set(l),{read:f,written:u}=c.input.regsAccessed;for(let _ of[f,u])for(let h of _){let g;h.startsWith("w")?g="x"+h.substring(1):g=h,p.delete(g)}if(p.size===0)break;a=d,l=p}while(a<e&&!c.eoi);n.availableScratchRegs=l}else do a=c.readOne();while(a<e&&!c.eoi);return a>=e}_allocateTrampoline(){It===null&&(It=tn(v===4?128:256));let e=bl[Process.arch],n,r,o=1,i={};if(v===4||this._canRelocateCode(e,i))n=e,r={};else{let s;Process.arch==="x64"?(n=5,s=Pa):Process.arch==="arm64"&&(n=8,s=Fa,o=4096),r={near:this.quickCodeAddress,maxDistance:s}}return this.redirectSize=n,this.trampoline=It.allocateSlice(r,o),i}_destroyTrampoline(){It.freeSlice(this.trampoline)}activate(e){let n=this._allocateTrampoline(),{trampoline:r,quickCode:o,redirectSize:i}=this,s=dl[Process.arch],c=s(r,o,i,n,e);this.overwrittenPrologueLength=c,this.overwrittenPrologue=Memory.dup(this.quickCodeAddress,c);let a=_l[Process.arch];a(o,r,i)}deactivate(){let{quickCodeAddress:e,overwrittenPrologueLength:n}=this,r=jn[Process.arch];Memory.patchCode(e,n,o=>{let i=new r(o,{pc:e}),{overwrittenPrologue:s}=this;i.putBytes(s.readByteArray(n)),i.flush()}),this._destroyTrampoline()}};function yl(t){let e=J(),{module:n,artClassLinker:r}=e;return t.equals(r.quickGenericJniTrampoline)||t.equals(r.quickToInterpreterBridgeTrampoline)||t.equals(r.quickResolutionTrampoline)||t.equals(r.quickImtConflictTrampoline)||t.compare(n.base)>=0&&t.compare(n.base.add(n.size))<0}var Rn=class{constructor(e){let n=Wn(e);this.methodId=n,this.originalMethod=null,this.hookedMethodId=n,this.replacementMethodId=null,this.interceptor=null}replace(e,n,r,o,i){let{kAccCompileDontBother:s,artNterpEntryPoint:c}=i;this.originalMethod=ro(this.methodId,o);let a=this.originalMethod.accessFlags;if((a&Oa)!==0&&El()){let u=this.originalMethod.jniCode;this.hookedMethodId=u.add(2*v).readPointer(),this.originalMethod=ro(this.hookedMethodId,o)}let{hookedMethodId:l}=this,d=Sl(l,o);this.replacementMethodId=d,Ct(d,{jniCode:e,accessFlags:(a&~(xa|La|Gr)|At|s)>>>0,quickCode:i.artClassLinker.quickGenericJniTrampoline,interpreterCode:i.artInterpreterToCompiledCodeBridge},o);let p=so|ka|Gr;(a&At)===0&&(p|=Na),Ct(l,{accessFlags:(a&~p|s)>>>0},o);let f=this.originalMethod.quickCode;if(c!==null&&f.equals(c)&&Ct(l,{quickCode:i.artQuickToInterpreterBridge},o),!yl(f)){let u=new kt(f);u.activate(o),this.interceptor=u}oe.replacedMethods.set(l,d),qc(l,o)}revert(e){let{hookedMethodId:n,interceptor:r}=this;Ct(n,this.originalMethod,e),oe.replacedMethods.delete(n),r!==null&&(r.deactivate(),this.interceptor=null)}resolveTarget(e,n,r,o){return this.hookedMethodId}};function El(){return ee()<28}function ro(t,e){let r=_e(e).offset;return["jniCode","accessFlags","quickCode","interpreterCode"].reduce((o,i)=>{let s=r[i];if(s===void 0)return o;let c=t.add(s),a=i==="accessFlags"?va:Sa;return o[i]=a.call(c),o},{})}function Ct(t,e,n){let o=_e(n).offset;Object.keys(e).forEach(i=>{let s=o[i];if(s===void 0)return;let c=t.add(s);(i==="accessFlags"?wa:Ia).call(c,e[i])})}var Pn=class{constructor(e){this.methodId=e,this.originalMethod=null}replace(e,n,r,o,i){let{methodId:s}=this;this.originalMethod=Memory.dup(s,yn);let c=r.reduce((f,u)=>f+u.size,0);n&&c++;let a=(s.add($r).readU32()|At)>>>0,l=c,d=0,p=c;s.add($r).writeU32(a),s.add(Ja).writeU16(l),s.add(Ga).writeU16(d),s.add($a).writeU16(p),s.add(Za).writeU32(vl(s)),i.dvmUseJNIBridge(s,e)}revert(e){Memory.copy(this.methodId,this.originalMethod,yn)}resolveTarget(e,n,r,o){let i=r.handle.add(ao).readPointer(),s;if(n)s=o.dvmDecodeIndirectRef(i,e.$h);else{let f=e.$borrowClassHandle(r);s=o.dvmDecodeIndirectRef(i,f.value),f.unref(r)}let c;n?c=s.add(za).readPointer():c=s;let a=c.toString(16),l=Lt.get(a);if(l===void 0){let f=c.add(Ba),u=c.add(Ua),_=f.readPointer(),h=u.readS32(),g=h*v,b=Memory.alloc(2*g);Memory.copy(b,_,g),f.writePointer(b),l={classObject:c,vtablePtr:f,vtableCountPtr:u,vtable:_,vtableCount:h,shadowVtable:b,shadowVtableCount:h,targetMethods:new Map},Lt.set(a,l)}let d=this.methodId.toString(16),p=l.targetMethods.get(d);if(p===void 0){p=Memory.dup(this.originalMethod,yn);let f=l.shadowVtableCount++;l.shadowVtable.add(f*v).writePointer(p),p.add(Va).writeU16(f),l.vtableCountPtr.writeS32(l.shadowVtableCount),l.targetMethods.set(d,p)}return p}};function vl(t){if(Process.arch!=="ia32")return Hr;let e=t.add(Ha).readPointer().readCString();if(e===null||e.length===0||e.length>65535)return Hr;let n;switch(e[0]){case"V":n=qa;break;case"F":n=Wa;break;case"D":n=Ka;break;case"J":n=Qa;break;case"Z":case"B":n=tc;break;case"C":n=ec;break;case"S":n=Xa;break;default:n=Ya;break}let r=0;for(let o=e.length-1;o>0;o--){let i=e[o];r+=i==="D"||i==="J"?2:1}return n<<nc|r}function Sl(t,e){let n=J();if(ee()<23){let r=n["art::Thread::CurrentFromGdb"]();return n["art::mirror::Object::Clone"](t,r)}return Memory.dup(t,_e(e).size)}function Kn(t,e,n){_o(t,e,xn,n)}function Qn(t,e){_o(t,e,Ln)}function Yn(t,e){let n=J();if(ee()<26)throw new Error("This API is only available on Android >= 8.0");ye(t,e,r=>{n["art::Runtime::DeoptimizeBootImage"](n.artRuntime)})}function _o(t,e,n,r){let o=J();if(ee()<24)throw new Error("This API is only available on Android >= 7.0");ye(t,e,i=>{if(ee()<30){if(!o.isJdwpStarted()){let c=wl(o);uc.push(c)}o.isDebuggerActive()||o["art::Dbg::GoActive"]();let s=Memory.alloc(8+v);switch(s.writeU32(n),n){case Ln:break;case xn:s.add(8).writePointer(r);break;default:throw new Error("Unsupported deoptimization kind")}o["art::Dbg::RequestDeoptimization"](s),o["art::Dbg::ManageDeoptimization"]()}else{let s=o.artInstrumentation;if(s===null)throw new Error("Unable to find Instrumentation class in ART; please file a bug");let c=o["art::Instrumentation::EnableDeoptimization"];switch(c!==void 0&&(s.add(sc().offset.deoptimizationEnabled).readU8()||c(s)),n){case Ln:o["art::Instrumentation::DeoptimizeEverything"](s,Memory.allocUtf8String("frida"));break;case xn:o["art::Instrumentation::Deoptimize"](s,r);break;default:throw new Error("Unsupported deoptimization kind")}}})}var Fn=class{constructor(){let e=Process.getModuleByName("libart.so"),n=e.getExportByName("_ZN3art4JDWP12JdwpAdbState6AcceptEv"),r=e.getExportByName("_ZN3art4JDWP12JdwpAdbState15ReceiveClientFdEv"),o=oo(),i=oo();this._controlFd=o[0],this._clientFd=i[0];let s=null;s=Interceptor.attach(n,function(c){let a=c[0];Memory.scanSync(a.add(8252),256,"00 ff ff ff ff 00")[0].address.add(1).writeS32(o[1]),s.detach()}),Interceptor.replace(r,new NativeCallback(function(c){return Interceptor.revert(r),i[1]},"int",["pointer"])),Interceptor.flush(),this._handshakeRequest=this._performHandshake()}async _performHandshake(){let e=new UnixInputStream(this._clientFd,{autoClose:!1}),n=new UnixOutputStream(this._clientFd,{autoClose:!1}),r=[74,68,87,80,45,72,97,110,100,115,104,97,107,101];try{await n.writeAll(r),await e.readAll(r.length)}catch{}}};function wl(t){let e=new Fn;t["art::Dbg::SetJdwpAllowed"](1);let n=Il();t["art::Dbg::ConfigureJdwp"](n);let r=t["art::InternalDebuggerControlCallback::StartDebugger"];return r!==void 0?r(NULL):t["art::Dbg::StartJdwp"](),e}function Il(){let t=ee()<28?2:3,e=0,n=t,r=!0,o=!1,i=e,s=8+at+2,c=Memory.alloc(s);return c.writeU32(n).add(4).writeU8(r?1:0).add(1).writeU8(o?1:0).add(1).add(at).writeU16(i),c}function oo(){wn===null&&(wn=new NativeFunction(Process.getModuleByName("libc.so").getExportByName("socketpair"),"int",["int","int","int","pointer"]));let t=Memory.alloc(8);if(wn(oc,ic,0,t)===-1)throw new Error("Unable to create socketpair for JDWP");return[t.readS32(),t.add(4).readS32()]}function Cl(t){let e=_c().offset,n=t.vm.add(e.globalsLock),r=t.vm.add(e.globals),o=t["art::IndirectReferenceTable::Add"],i=t["art::ReaderWriterMutex::ExclusiveLock"],s=t["art::ReaderWriterMutex::ExclusiveUnlock"],c=0;return function(a,l,d){i(n,l);try{return o(r,c,d)}finally{s(n,l)}}}function Tl(t){let e=t["art::Thread::DecodeJObject"];if(e===void 0)throw new Error("art::Thread::DecodeJObject is not available; please file a bug");return function(n,r,o){return e(r,o)}}var Al={ia32:io,x64:io,arm:Ll,arm64:xl};function mo(t,e,n){let r=J(),o=e.handle.readPointer(),i,s=r.find("_ZN3art3JNIILb1EE14ExceptionClearEP7_JNIEnv");s!==null?i=s:i=o.add(Ot).readPointer();let c,a=r.find("_ZN3art3JNIILb1EE10FatalErrorEP7_JNIEnvPKc");a!==null?c=a:c=o.add(Da).readPointer();let l=Al[Process.arch];if(l===void 0)throw new Error("Not yet implemented for "+Process.arch);let d=null,p=Ze(t).offset,f=p.exception,u=new Set,_=p.isExceptionReportedToInstrumentation;_!==null&&u.add(_);let h=p.throwLocation;h!==null&&(u.add(h),u.add(h+v),u.add(h+2*v));let g=65536,b=Memory.alloc(g);return Memory.patchCode(b,g,E=>{d=l(E,b,i,c,f,u,n)}),d._code=b,d._callback=n,d}function io(t,e,n,r,o,i,s){let c={},a=new Set,l=[n];for(;l.length>0;){let h=l.shift();if(Object.values(c).some(({begin:M,end:O})=>h.compare(M)>=0&&h.compare(O)<0))continue;let b=h.toString(),E={begin:h},C=null,A=!1;do{if(h.equals(r)){A=!0;break}let M=Instruction.parse(h);C=M;let O=c[M.address.toString()];if(O!==void 0){delete c[O.begin.toString()],c[b]=O,O.begin=E.begin,E=null;break}let k=null;switch(M.mnemonic){case"jmp":k=ptr(M.operands[0].value),A=!0;break;case"je":case"jg":case"jle":case"jne":case"js":k=ptr(M.operands[0].value);break;case"ret":A=!0;break}k!==null&&(a.add(k.toString()),l.push(k),l.sort((N,S)=>N.compare(S))),h=M.next}while(!A);E!==null&&(E.end=C.address.add(C.size),c[b]=E)}let d=Object.keys(c).map(h=>c[h]);d.sort((h,g)=>h.begin.compare(g.begin));let p=c[n.toString()];d.splice(d.indexOf(p),1),d.unshift(p);let f=new X86Writer(t,{pc:e}),u=!1,_=null;return d.forEach(h=>{let g=h.end.sub(h.begin).toInt32(),b=new X86Relocator(h.begin,f),E;for(;(E=b.readOne())!==0;){let C=b.input,{mnemonic:A}=C,M=C.address.toString();a.has(M)&&f.putLabel(M);let O=!0;switch(A){case"jmp":f.putJmpNearLabel(fe(C.operands[0])),O=!1;break;case"je":case"jg":case"jle":case"jne":case"js":f.putJccNearLabel(A,fe(C.operands[0]),"no-hint"),O=!1;break;case"mov":{let[k,N]=C.operands;if(k.type==="mem"&&N.type==="imm"){let S=k.value,L=S.disp;if(L===o&&N.value.valueOf()===0){if(_=S.base,f.putPushfx(),f.putPushax(),f.putMovRegReg("xbp","xsp"),v===4)f.putAndRegU32("esp",4294967280);else{let j=_!=="rdi"?"rdi":"rsi";f.putMovRegU64(j,uint64("0xfffffffffffffff0")),f.putAndRegReg("rsp",j)}f.putCallAddressWithAlignedArguments(s,[_]),f.putMovRegReg("xsp","xbp"),f.putPopax(),f.putPopfx(),u=!0,O=!1}else i.has(L)&&S.base===_&&(O=!1)}break}case"call":{let k=C.operands[0];k.type==="mem"&&k.value.disp===Ot&&(v===4?(f.putPopReg("eax"),f.putMovRegRegOffsetPtr("eax","eax",4),f.putPushReg("eax")):f.putMovRegRegOffsetPtr("rdi","rdi",8),f.putCallAddressWithArguments(s,[]),u=!0,O=!1);break}}if(O?b.writeAll():b.skipOne(),E===g)break}b.dispose()}),f.dispose(),u||Xn(),new NativeFunction(e,"void",["pointer"],W)}function Ll(t,e,n,r,o,i,s){let c={},a=new Set,l=ptr(1).not(),d=[n];for(;d.length>0;){let b=d.shift();if(Object.values(c).some(({begin:L,end:j})=>b.compare(L)>=0&&b.compare(j)<0))continue;let C=b.and(l),A=C.toString(),M=b.and(1),O={begin:C},k=null,N=!1,S=0;do{if(b.equals(r)){N=!0;break}let L=Instruction.parse(b),{mnemonic:j}=L;k=L;let R=b.and(l).toString(),D=c[R];if(D!==void 0){delete c[D.begin.toString()],c[A]=D,D.begin=O.begin,O=null;break}let U=S===0,F=null;switch(j){case"b":F=ptr(L.operands[0].value),N=U;break;case"beq.w":case"beq":case"bne":case"bne.w":case"bgt":F=ptr(L.operands[0].value);break;case"cbz":case"cbnz":F=ptr(L.operands[1].value);break;case"pop.w":U&&(N=L.operands.filter(z=>z.value==="pc").length===1);break}switch(j){case"it":S=1;break;case"itt":S=2;break;case"ittt":S=3;break;case"itttt":S=4;break;default:S>0&&S--;break}F!==null&&(a.add(F.toString()),d.push(F.or(M)),d.sort((z,X)=>z.compare(X))),b=L.next}while(!N);O!==null&&(O.end=k.address.add(k.size),c[A]=O)}let p=Object.keys(c).map(b=>c[b]);p.sort((b,E)=>b.begin.compare(E.begin));let f=c[n.and(l).toString()];p.splice(p.indexOf(f),1),p.unshift(f);let u=new ThumbWriter(t,{pc:e}),_=!1,h=null,g=null;return p.forEach(b=>{let E=new ThumbRelocator(b.begin,u),C=b.begin,A=b.end,M=0;do{if(E.readOne()===0)throw new Error("Unexpected end of block");let k=E.input;C=k.address,M=k.size;let{mnemonic:N}=k,S=C.toString();a.has(S)&&u.putLabel(S);let L=!0;switch(N){case"b":u.putBLabel(fe(k.operands[0])),L=!1;break;case"beq.w":u.putBCondLabelWide("eq",fe(k.operands[0])),L=!1;break;case"bne.w":u.putBCondLabelWide("ne",fe(k.operands[0])),L=!1;break;case"beq":case"bne":case"bgt":u.putBCondLabelWide(N.substr(1),fe(k.operands[0])),L=!1;break;case"cbz":{let j=k.operands;u.putCbzRegLabel(j[0].value,fe(j[1])),L=!1;break}case"cbnz":{let j=k.operands;u.putCbnzRegLabel(j[0].value,fe(j[1])),L=!1;break}case"str":case"str.w":{let j=k.operands[1].value,w=j.disp;if(w===o){h=j.base;let R=h!=="r4"?"r4":"r5",D=["r0","r1","r2","r3",R,"r9","r12","lr"];u.putPushRegs(D),u.putMrsRegReg(R,"apsr-nzcvq"),u.putCallAddressWithArguments(s,[h]),u.putMsrRegReg("apsr-nzcvq",R),u.putPopRegs(D),_=!0,L=!1}else i.has(w)&&j.base===h&&(L=!1);break}case"ldr":{let[j,w]=k.operands;if(w.type==="mem"){let R=w.value;R.base[0]==="r"&&R.disp===Ot&&(g=j.value)}break}case"blx":k.operands[0].value===g&&(u.putLdrRegRegOffset("r0","r0",4),u.putCallAddressWithArguments(s,["r0"]),_=!0,g=null,L=!1);break}L?E.writeAll():E.skipOne()}while(!C.add(M).equals(A));E.dispose()}),u.dispose(),_||Xn(),new NativeFunction(e.or(1),"void",["pointer"],W)}function xl(t,e,n,r,o,i,s){let c={},a=new Set,l=[n];for(;l.length>0;){let b=l.shift();if(Object.values(c).some(({begin:k,end:N})=>b.compare(k)>=0&&b.compare(N)<0))continue;let C=b.toString(),A={begin:b},M=null,O=!1;do{if(b.equals(r)){O=!0;break}let k;try{k=Instruction.parse(b)}catch(L){if(b.readU32()===0){O=!0;break}else throw L}M=k;let N=c[k.address.toString()];if(N!==void 0){delete c[N.begin.toString()],c[C]=N,N.begin=A.begin,A=null;break}let S=null;switch(k.mnemonic){case"b":S=ptr(k.operands[0].value),O=!0;break;case"b.eq":case"b.ne":case"b.le":case"b.gt":S=ptr(k.operands[0].value);break;case"cbz":case"cbnz":S=ptr(k.operands[1].value);break;case"tbz":case"tbnz":S=ptr(k.operands[2].value);break;case"ret":O=!0;break}S!==null&&(a.add(S.toString()),l.push(S),l.sort((L,j)=>L.compare(j))),b=k.next}while(!O);A!==null&&(A.end=M.address.add(M.size),c[C]=A)}let d=Object.keys(c).map(b=>c[b]);d.sort((b,E)=>b.begin.compare(E.begin));let p=c[n.toString()];d.splice(d.indexOf(p),1),d.unshift(p);let f=new Arm64Writer(t,{pc:e});f.putBLabel("performTransition");let u=e.add(f.offset);f.putPushAllXRegisters(),f.putCallAddressWithArguments(s,["x0"]),f.putPopAllXRegisters(),f.putRet(),f.putLabel("performTransition");let _=!1,h=null,g=null;return d.forEach(b=>{let E=b.end.sub(b.begin).toInt32(),C=new Arm64Relocator(b.begin,f),A;for(;(A=C.readOne())!==0;){let M=C.input,{mnemonic:O}=M,k=M.address.toString();a.has(k)&&f.putLabel(k);let N=!0;switch(O){case"b":f.putBLabel(fe(M.operands[0])),N=!1;break;case"b.eq":case"b.ne":case"b.le":case"b.gt":f.putBCondLabel(O.substr(2),fe(M.operands[0])),N=!1;break;case"cbz":{let S=M.operands;f.putCbzRegLabel(S[0].value,fe(S[1])),N=!1;break}case"cbnz":{let S=M.operands;f.putCbnzRegLabel(S[0].value,fe(S[1])),N=!1;break}case"tbz":{let S=M.operands;f.putTbzRegImmLabel(S[0].value,S[1].value.valueOf(),fe(S[2])),N=!1;break}case"tbnz":{let S=M.operands;f.putTbnzRegImmLabel(S[0].value,S[1].value.valueOf(),fe(S[2])),N=!1;break}case"str":{let S=M.operands,L=S[0].value,j=S[1].value,w=j.disp;L==="xzr"&&w===o?(h=j.base,f.putPushRegReg("x0","lr"),f.putMovRegReg("x0",h),f.putBlImm(u),f.putPopRegReg("x0","lr"),_=!0,N=!1):i.has(w)&&j.base===h&&(N=!1);break}case"ldr":{let S=M.operands,L=S[1].value;L.base[0]==="x"&&L.disp===Ot&&(g=S[0].value);break}case"blr":M.operands[0].value===g&&(f.putLdrRegRegOffset("x0","x0",8),f.putCallAddressWithArguments(s,["x0"]),_=!0,g=null,N=!1);break}if(N?C.writeAll():C.skipOne(),A===E)break}C.dispose()}),f.dispose(),_||Xn(),new NativeFunction(e,"void",["pointer"],W)}function Xn(){throw new Error("Unable to parse ART internals; please file a bug")}function Nl(t){let e=t["art::ArtMethod::PrettyMethod"];e!==void 0&&(Interceptor.attach(e.impl,oe.hooks.ArtMethod.prettyMethod),Interceptor.flush())}function fe(t){return ptr(t.value).toString()}function kl(t,e){return new NativeFunction(t,"pointer",e,W)}function Ml(t,e){let n=new NativeFunction(t,"void",["pointer"].concat(e),W);return function(){let r=Memory.alloc(v);return n(r,...arguments),r.readPointer()}}function Tt(t,e){let{arch:n}=Process;switch(n){case"ia32":case"arm64":{let r;n==="ia32"?r=He(64,s=>{let c=1+e.length,a=c*4;s.putSubRegImm("esp",a);for(let l=0;l!==c;l++){let d=l*4;s.putMovRegRegOffsetPtr("eax","esp",a+4+d),s.putMovRegOffsetPtrReg("esp",d,"eax")}s.putCallAddress(t),s.putAddRegImm("esp",a-4),s.putRet()}):r=He(32,s=>{s.putMovRegReg("x8","x0"),e.forEach((c,a)=>{s.putMovRegReg("x"+a,"x"+(a+1))}),s.putLdrRegAddress("x7",t),s.putBrReg("x7")});let o=new NativeFunction(r,"void",["pointer"].concat(e),W),i=function(...s){o(...s)};return i.handle=r,i.impl=t,i}default:{let r=new NativeFunction(t,"void",["pointer"].concat(e),W);return r.impl=t,r}}}var Mt=class{constructor(){this.handle=Memory.alloc(at)}dispose(){let[e,n]=this._getData();n||J().$delete(e)}disposeToString(){let e=this.toString();return this.dispose(),e}toString(){let[e]=this._getData();return e.readUtf8String()}_getData(){let e=this.handle,n=(e.readU8()&1)===0;return[n?e.add(1):e.add(2*v).readPointer(),n]}},Dn=class{$delete(){this.dispose(),J().$delete(this)}constructor(e,n){this.handle=e,this._begin=e,this._end=e.add(v),this._storage=e.add(2*v),this._elementSize=n}init(){this.begin=NULL,this.end=NULL,this.storage=NULL}dispose(){J().$delete(this.begin)}get begin(){return this._begin.readPointer()}set begin(e){this._begin.writePointer(e)}get end(){return this._end.readPointer()}set end(e){this._end.writePointer(e)}get storage(){return this._storage.readPointer()}set storage(e){this._storage.writePointer(e)}get size(){return this.end.sub(this.begin).toInt32()/this._elementSize}},ct=class t extends Dn{static $new(){let e=new t(J().$new(rc));return e.init(),e}constructor(e){super(e,v)}get handles(){let e=[],n=this.begin,r=this.end;for(;!n.equals(r);)e.push(n.readPointer()),n=n.add(v);return e}},jl=0,go=v,bo=go+4,Ol=-1,jt=class t{$delete(){this.dispose(),J().$delete(this)}constructor(e){this.handle=e,this._link=e.add(jl),this._numberOfReferences=e.add(go)}init(e,n){this.link=e,this.numberOfReferences=n}dispose(){}get link(){return new t(this._link.readPointer())}set link(e){this._link.writePointer(e)}get numberOfReferences(){return this._numberOfReferences.readS32()}set numberOfReferences(e){this._numberOfReferences.writeS32(e)}},yo=Dl(bo),Eo=yo+v,Rl=Eo+v,lt=class t extends jt{static $new(e,n){let r=new t(J().$new(Rl));return r.init(e,n),r}constructor(e){super(e),this._self=e.add(yo),this._currentScope=e.add(Eo);let o=(64-v-4-4)/4;this._scopeLayout=st.layoutForCapacity(o),this._topHandleScopePtr=null}init(e,n){let r=e.add(Ze(n).offset.topHandleScope);this._topHandleScopePtr=r,super.init(r.readPointer(),Ol),this.self=e,this.currentScope=st.$new(this._scopeLayout),r.writePointer(this)}dispose(){this._topHandleScopePtr.writePointer(this.link);let e;for(;(e=this.currentScope)!==null;){let n=e.link;e.$delete(),this.currentScope=n}}get self(){return this._self.readPointer()}set self(e){this._self.writePointer(e)}get currentScope(){let e=this._currentScope.readPointer();return e.isNull()?null:new st(e,this._scopeLayout)}set currentScope(e){this._currentScope.writePointer(e)}newHandle(e){return this.currentScope.newHandle(e)}},st=class t extends jt{static $new(e){let n=new t(J().$new(e.size),e);return n.init(),n}constructor(e,n){super(e);let{offset:r}=n;this._refsStorage=e.add(r.refsStorage),this._pos=e.add(r.pos),this._layout=n}init(){super.init(NULL,this._layout.numberOfReferences),this.pos=0}get pos(){return this._pos.readU32()}set pos(e){this._pos.writeU32(e)}newHandle(e){let n=this.pos,r=this._refsStorage.add(n*4);return r.writeS32(e.toInt32()),this.pos=n+1,r}static layoutForCapacity(e){let n=bo,r=n+e*4;return{size:r+4,numberOfReferences:e,offset:{refsStorage:n,pos:r}}}},Pl={arm:function(t,e){let n=Process.pageSize,r=Memory.alloc(n);Memory.protect(r,n,"rwx");let o=new NativeCallback(e,"void",["pointer"]);r._onMatchCallback=o;let i=[26625,18947,17041,53505,19202,18200,18288,48896],s=i.length*2,c=s+4,a=c+4;return Memory.patchCode(r,a,function(l){i.forEach((d,p)=>{l.add(p*2).writeU16(d)}),l.add(s).writeS32(t),l.add(c).writePointer(o)}),r.or(1)},arm64:function(t,e){let n=Process.pageSize,r=Memory.alloc(n);Memory.protect(r,n,"rwx");let o=new NativeCallback(e,"void",["pointer"]);r._onMatchCallback=o;let i=[3107979265,402653378,1795293247,1409286241,1476395139,3592355936,3596551104],s=i.length*4,c=s+4,a=c+8;return Memory.patchCode(r,a,function(l){i.forEach((d,p)=>{l.add(p*4).writeU32(d)}),l.add(s).writeS32(t),l.add(c).writePointer(o)}),r}};function er(t,e){return(Pl[Process.arch]||Fl)(t,e)}function Fl(t,e){return new NativeCallback(n=>{n.readS32()===t&&e(n)},"void",["pointer","pointer"])}function Dl(t){let e=t%v;return e!==0?t+v-e:t}var Ul=4,{pointerSize:V}=Process,Bl=256,zl=65536,Vl=131072,Jl=33554432,Gl=67108864,$l=134217728,De={exceptions:"propagate"},Io=le(od),Hl=le(sd),Zl=le(td),tr=null,nr=!1,Dt=new Map,ut=new Map;function Ce(){return tr===null&&(tr=ql()),tr}function ql(){let t=Process.enumerateModules().filter(a=>/jvm.(dll|dylib|so)$/.test(a.name));if(t.length===0)return null;let e=t[0],n={flavor:"jvm"},r=Process.platform==="windows"?[{module:e,functions:{JNI_GetCreatedJavaVMs:["JNI_GetCreatedJavaVMs","int",["pointer","int","pointer"]],JVM_Sleep:["JVM_Sleep","void",["pointer","pointer","long"]],"VMThread::execute":["VMThread::execute","void",["pointer"]],"Method::size":["Method::size","int",["int"]],"Method::set_native_function":["Method::set_native_function","void",["pointer","pointer","int"]],"Method::clear_native_function":["Method::clear_native_function","void",["pointer"]],"Method::jmethod_id":["Method::jmethod_id","pointer",["pointer"]],"ClassLoaderDataGraph::classes_do":["ClassLoaderDataGraph::classes_do","void",["pointer"]],"NMethodSweeper::sweep_code_cache":["NMethodSweeper::sweep_code_cache","void",[]],"OopMapCache::flush_obsolete_entries":["OopMapCache::flush_obsolete_entries","void",["pointer"]]},variables:{"VM_RedefineClasses::`vftable'":function(a){this.vtableRedefineClasses=a},"VM_RedefineClasses::doit":function(a){this.redefineClassesDoIt=a},"VM_RedefineClasses::doit_prologue":function(a){this.redefineClassesDoItPrologue=a},"VM_RedefineClasses::doit_epilogue":function(a){this.redefineClassesDoItEpilogue=a},"VM_RedefineClasses::allow_nested_vm_operations":function(a){this.redefineClassesAllow=a},"NMethodSweeper::_traversals":function(a){this.traversals=a},"NMethodSweeper::_should_sweep":function(a){this.shouldSweep=a}},optionals:[]}]:[{module:e,functions:{JNI_GetCreatedJavaVMs:["JNI_GetCreatedJavaVMs","int",["pointer","int","pointer"]],_ZN6Method4sizeEb:["Method::size","int",["int"]],_ZN6Method19set_native_functionEPhb:["Method::set_native_function","void",["pointer","pointer","int"]],_ZN6Method21clear_native_functionEv:["Method::clear_native_function","void",["pointer"]],_ZN6Method24restore_unshareable_infoEP10JavaThread:["Method::restore_unshareable_info","void",["pointer","pointer"]],_ZN6Method24restore_unshareable_infoEP6Thread:["Method::restore_unshareable_info","void",["pointer","pointer"]],_ZN6Method11link_methodERK12methodHandleP10JavaThread:["Method::link_method","void",["pointer","pointer","pointer"]],_ZN6Method10jmethod_idEv:["Method::jmethod_id","pointer",["pointer"]],_ZN6Method10clear_codeEv:function(a){let l=new NativeFunction(a,"void",["pointer"],De);this["Method::clear_code"]=function(d){l(d)}},_ZN6Method10clear_codeEb:function(a){let l=new NativeFunction(a,"void",["pointer","int"],De),d=0;this["Method::clear_code"]=function(p){l(p,d)}},_ZN18VM_RedefineClasses19mark_dependent_codeEP13InstanceKlass:["VM_RedefineClasses::mark_dependent_code","void",["pointer","pointer"]],_ZN18VM_RedefineClasses20flush_dependent_codeEv:["VM_RedefineClasses::flush_dependent_code","void",[]],_ZN18VM_RedefineClasses20flush_dependent_codeEP13InstanceKlassP6Thread:["VM_RedefineClasses::flush_dependent_code","void",["pointer","pointer","pointer"]],_ZN18VM_RedefineClasses20flush_dependent_codeE19instanceKlassHandleP6Thread:["VM_RedefineClasses::flush_dependent_code","void",["pointer","pointer","pointer"]],_ZN19ResolvedMethodTable21adjust_method_entriesEPb:["ResolvedMethodTable::adjust_method_entries","void",["pointer"]],_ZN15MemberNameTable21adjust_method_entriesEP13InstanceKlassPb:["MemberNameTable::adjust_method_entries","void",["pointer","pointer","pointer"]],_ZN17ConstantPoolCache21adjust_method_entriesEPb:function(a){let l=new NativeFunction(a,"void",["pointer","pointer"],De);this["ConstantPoolCache::adjust_method_entries"]=function(d,p,f){l(d,f)}},_ZN17ConstantPoolCache21adjust_method_entriesEP13InstanceKlassPb:function(a){let l=new NativeFunction(a,"void",["pointer","pointer","pointer"],De);this["ConstantPoolCache::adjust_method_entries"]=function(d,p,f){l(d,p,f)}},_ZN20ClassLoaderDataGraph10classes_doEP12KlassClosure:["ClassLoaderDataGraph::classes_do","void",["pointer"]],_ZN20ClassLoaderDataGraph22clean_deallocate_listsEb:["ClassLoaderDataGraph::clean_deallocate_lists","void",["int"]],_ZN10JavaThread27thread_from_jni_environmentEP7JNIEnv_:["JavaThread::thread_from_jni_environment","pointer",["pointer"]],_ZN8VMThread7executeEP12VM_Operation:["VMThread::execute","void",["pointer"]],_ZN11OopMapCache22flush_obsolete_entriesEv:["OopMapCache::flush_obsolete_entries","void",["pointer"]],_ZN14NMethodSweeper11force_sweepEv:["NMethodSweeper::force_sweep","void",[]],_ZN14NMethodSweeper16sweep_code_cacheEv:["NMethodSweeper::sweep_code_cache","void",[]],_ZN14NMethodSweeper17sweep_in_progressEv:["NMethodSweeper::sweep_in_progress","bool",[]],JVM_Sleep:["JVM_Sleep","void",["pointer","pointer","long"]]},variables:{_ZN18VM_RedefineClasses14_the_class_oopE:function(a){this.redefineClass=a},_ZN18VM_RedefineClasses10_the_classE:function(a){this.redefineClass=a},_ZN18VM_RedefineClasses25AdjustCpoolCacheAndVtable8do_klassEP5Klass:function(a){this.doKlass=a},_ZN18VM_RedefineClasses22AdjustAndCleanMetadata8do_klassEP5Klass:function(a){this.doKlass=a},_ZTV18VM_RedefineClasses:function(a){this.vtableRedefineClasses=a},_ZN18VM_RedefineClasses4doitEv:function(a){this.redefineClassesDoIt=a},_ZN18VM_RedefineClasses13doit_prologueEv:function(a){this.redefineClassesDoItPrologue=a},_ZN18VM_RedefineClasses13doit_epilogueEv:function(a){this.redefineClassesDoItEpilogue=a},_ZN18VM_RedefineClassesD0Ev:function(a){this.redefineClassesDispose0=a},_ZN18VM_RedefineClassesD1Ev:function(a){this.redefineClassesDispose1=a},_ZNK18VM_RedefineClasses26allow_nested_vm_operationsEv:function(a){this.redefineClassesAllow=a},_ZNK18VM_RedefineClasses14print_on_errorEP12outputStream:function(a){this.redefineClassesOnError=a},_ZN13InstanceKlass33create_new_default_vtable_indicesEiP10JavaThread:function(a){this.createNewDefaultVtableIndices=a},_ZN13InstanceKlass33create_new_default_vtable_indicesEiP6Thread:function(a){this.createNewDefaultVtableIndices=a},_ZN19Abstract_VM_Version19jre_release_versionEv:function(a){let d=new NativeFunction(a,"pointer",[],De)().readCString();this.version=d.startsWith("1.8")?8:d.startsWith("9.")?9:parseInt(d.slice(0,2),10),this.versionS=d},_ZN14NMethodSweeper11_traversalsE:function(a){this.traversals=a},_ZN14NMethodSweeper21_sweep_fractions_leftE:function(a){this.fractions=a},_ZN14NMethodSweeper13_should_sweepE:function(a){this.shouldSweep=a}},optionals:["_ZN6Method24restore_unshareable_infoEP10JavaThread","_ZN6Method24restore_unshareable_infoEP6Thread","_ZN6Method11link_methodERK12methodHandleP10JavaThread","_ZN6Method10clear_codeEv","_ZN6Method10clear_codeEb","_ZN18VM_RedefineClasses19mark_dependent_codeEP13InstanceKlass","_ZN18VM_RedefineClasses20flush_dependent_codeEv","_ZN18VM_RedefineClasses20flush_dependent_codeEP13InstanceKlassP6Thread","_ZN18VM_RedefineClasses20flush_dependent_codeE19instanceKlassHandleP6Thread","_ZN19ResolvedMethodTable21adjust_method_entriesEPb","_ZN15MemberNameTable21adjust_method_entriesEP13InstanceKlassPb","_ZN17ConstantPoolCache21adjust_method_entriesEPb","_ZN17ConstantPoolCache21adjust_method_entriesEP13InstanceKlassPb","_ZN20ClassLoaderDataGraph22clean_deallocate_listsEb","_ZN10JavaThread27thread_from_jni_environmentEP7JNIEnv_","_ZN14NMethodSweeper11force_sweepEv","_ZN14NMethodSweeper17sweep_in_progressEv","_ZN18VM_RedefineClasses14_the_class_oopE","_ZN18VM_RedefineClasses10_the_classE","_ZN18VM_RedefineClasses25AdjustCpoolCacheAndVtable8do_klassEP5Klass","_ZN18VM_RedefineClasses22AdjustAndCleanMetadata8do_klassEP5Klass","_ZN18VM_RedefineClassesD0Ev","_ZN18VM_RedefineClassesD1Ev","_ZNK18VM_RedefineClasses14print_on_errorEP12outputStream","_ZN13InstanceKlass33create_new_default_vtable_indicesEiP10JavaThread","_ZN13InstanceKlass33create_new_default_vtable_indicesEiP6Thread","_ZN14NMethodSweeper21_sweep_fractions_leftE"]}],o=[];if(r.forEach(function(a){let l=a.module,d=a.functions||{},p=a.variables||{},f=new Set(a.optionals||[]),u=l.enumerateExports().reduce(function(h,g){return h[g.name]=g,h},{}),_=l.enumerateSymbols().reduce(function(h,g){return h[g.name]=g,h},u);Object.keys(d).forEach(function(h){let g=_[h];if(g!==void 0){let b=d[h];typeof b=="function"?b.call(n,g.address):n[b[0]]=new NativeFunction(g.address,b[1],b[2],De)}else f.has(h)||o.push(h)}),Object.keys(p).forEach(function(h){let g=_[h];g!==void 0?p[h].call(n,g.address):f.has(h)||o.push(h)})}),o.length>0)throw new Error("Java API only partially available; please file a bug. Missing: "+o.join(", "));let i=Memory.alloc(V),s=Memory.alloc(Ul);if(de("JNI_GetCreatedJavaVMs",n.JNI_GetCreatedJavaVMs(i,1,s)),s.readInt()===0)return null;n.vm=i.readPointer();let c=Process.platform==="windows"?{$new:["??2@YAPEAX_K@Z","pointer",["ulong"]],$delete:["??3@YAXPEAX@Z","void",["pointer"]]}:{$new:["_Znwm","pointer",["ulong"]],$delete:["_ZdlPv","void",["pointer"]]};for(let[a,[l,d,p]]of Object.entries(c)){let f=Module.findGlobalExportByName(l);if(f===null&&(f=DebugSymbol.fromName(l).address,f.isNull()))throw new Error(`unable to find C++ allocator API, missing: '${l}'`);n[a]=new NativeFunction(f,d,p,De)}return n.jvmti=Wl(n),n["JavaThread::thread_from_jni_environment"]===void 0&&(n["JavaThread::thread_from_jni_environment"]=Ql(n)),n}function Wl(t){let e=new Ie(t),n;return e.perform(()=>{let r=e.tryGetEnvHandle(yt.v1_0);if(r===null)throw new Error("JVMTI not available");n=new Le(r,e);let o=Memory.alloc(8);o.writeU64(Et.canTagObjects);let i=n.addCapabilities(o);de("getEnvJvmti::AddCapabilities",i)}),n}var Kl={x64:Yl};function Ql(t){let e=null,n=Kl[Process.arch];if(n!==void 0){let o=new Ie(t).perform(i=>i.handle.readPointer().add(6*V).readPointer());e=xe(o,n,{limit:11})}return e===null?()=>{throw new Error("Unable to make thread_from_jni_environment() helper for the current architecture")}:r=>r.add(e)}function Yl(t){if(t.mnemonic!=="lea")return null;let{base:e,disp:n}=t.operands[1].value;return e==="rdi"&&n<0?n:null}function Co(t,e){}var rr=class{constructor(e){this.methodId=e,this.method=e.readPointer(),this.originalMethod=null,this.newMethod=null,this.resolved=null,this.impl=null,this.key=e.toString(16)}replace(e,n,r,o,i){let{key:s}=this,c=ut.get(s);c!==void 0&&(ut.delete(s),this.method=c.method,this.originalMethod=c.originalMethod,this.newMethod=c.newMethod,this.resolved=c.resolved),this.impl=e,Dt.set(s,this),vo(o)}revert(e){let{key:n}=this;Dt.delete(n),ut.set(n,this),vo(e)}resolveTarget(e,n,r,o){let{resolved:i,originalMethod:s,methodId:c}=this;if(i!==null)return i;if(s===null)return c;s.oldMethod.vtableIndexPtr.writeS32(-2);let l=Memory.alloc(V);return l.writePointer(this.method),this.resolved=l,l}};function vo(t){nr||(nr=!0,Script.nextTick(Xl,t))}function Xl(t){let e=new Map(Dt),n=new Map(ut);Dt.clear(),ut.clear(),nr=!1,t.perform(r=>{let o=Ce(),i=o["JavaThread::thread_from_jni_environment"](r.handle),s=!1;To(()=>{e.forEach(c=>{let{method:a,originalMethod:l,impl:d,methodId:p,newMethod:f}=c;l===null?(c.originalMethod=Lo(a),c.newMethod=nd(a,d,i),So(c.newMethod,p,i)):o["Method::set_native_function"](f.method,d,0)}),n.forEach(c=>{let{originalMethod:a,methodId:l,newMethod:d}=c;if(a!==null){rd(a);let p=a.oldMethod;p.oldMethod=d,So(p,l,i),s=!0}})}),s&&ed(r.handle)})}function ed(t){let{fractions:e,shouldSweep:n,traversals:r,"NMethodSweeper::sweep_code_cache":o,"NMethodSweeper::sweep_in_progress":i,"NMethodSweeper::force_sweep":s,JVM_Sleep:c}=Ce();if(s!==void 0)Thread.sleep(.05),s(),Thread.sleep(.05),s();else{let a=r.readS64(),l=a+2;for(;l>a;)e.writeS32(1),c(t,NULL,50),i()||To(()=>{Thread.sleep(.05)}),n.readU8()===0&&(e.writeS32(1),o()),a=r.readS64()}}function To(t,e,n){let{execute:r,vtable:o,vtableSize:i,doItOffset:s,prologueOffset:c,epilogueOffset:a}=Zl(),l=Memory.dup(o,i),d=Memory.alloc(V*25);d.writePointer(l);let p=new NativeCallback(t,"void",["pointer"]);l.add(s).writePointer(p);let f=null;e!==void 0&&(f=new NativeCallback(e,"int",["pointer"]),l.add(c).writePointer(f));let u=null;n!==void 0&&(u=new NativeCallback(n,"void",["pointer"]),l.add(a).writePointer(u)),r(d)}function td(){let{vtableRedefineClasses:t,redefineClassesDoIt:e,redefineClassesDoItPrologue:n,redefineClassesDoItEpilogue:r,redefineClassesOnError:o,redefineClassesAllow:i,redefineClassesDispose0:s,redefineClassesDispose1:c,"VMThread::execute":a}=Ce(),l=t.add(2*V),d=15*V,p=Memory.dup(l,d),f=new NativeCallback(()=>{},"void",["pointer"]),u,_,h;for(let g=0;g!==d;g+=V){let b=p.add(g),E=b.readPointer();o!==void 0&&E.equals(o)||s!==void 0&&E.equals(s)||c!==void 0&&E.equals(c)?b.writePointer(f):E.equals(e)?u=g:E.equals(n)?(_=g,b.writePointer(i)):E.equals(r)&&(h=g,b.writePointer(f))}return{execute:a,emptyCallback:f,vtable:p,vtableSize:d,doItOffset:u,prologueOffset:_,epilogueOffset:h}}function Ao(t){return new rr(t)}function So(t,e,n){let{method:r,oldMethod:o}=t,i=Ce();t.methodsArray.add(t.methodIndex*V).writePointer(r),t.vtableIndex>=0&&t.vtable.add(t.vtableIndex*V).writePointer(r),e.writePointer(r),o.accessFlagsPtr.writeU32((o.accessFlags|zl|Vl)>>>0);let s=i["OopMapCache::flush_obsolete_entries"];if(s!==void 0){let{oopMapCache:_}=t;_.isNull()||s(_)}let c=i["VM_RedefineClasses::mark_dependent_code"],a=i["VM_RedefineClasses::flush_dependent_code"];c!==void 0?(c(NULL,t.instanceKlass),a()):a(NULL,t.instanceKlass,n);let l=Memory.alloc(1);l.writeU8(1),i["ConstantPoolCache::adjust_method_entries"](t.cache,t.instanceKlass,l);let d=Memory.alloc(3*V),p=Memory.alloc(V);p.writePointer(i.doKlass),d.writePointer(p),d.add(V).writePointer(n),d.add(2*V).writePointer(n),i.redefineClass!==void 0&&i.redefineClass.writePointer(t.instanceKlass),i["ClassLoaderDataGraph::classes_do"](d);let f=i["ResolvedMethodTable::adjust_method_entries"];if(f!==void 0)f(l);else{let{memberNames:_}=t;if(!_.isNull()){let h=i["MemberNameTable::adjust_method_entries"];h!==void 0&&h(_,t.instanceKlass,l)}}let u=i["ClassLoaderDataGraph::clean_deallocate_lists"];u!==void 0&&u(0)}function nd(t,e,n){let r=Ce(),o=Lo(t);o.constPtr.writePointer(o.const);let i=(o.accessFlags|Bl|Jl|Gl|$l)>>>0;if(o.accessFlagsPtr.writeU32(i),o.signatureHandler.writePointer(NULL),o.adapter.writePointer(NULL),o.i2iEntry.writePointer(NULL),r["Method::clear_code"](o.method),o.dataPtr.writePointer(NULL),o.countersPtr.writePointer(NULL),o.stackmapPtr.writePointer(NULL),r["Method::clear_native_function"](o.method),r["Method::set_native_function"](o.method,e,0),r["Method::restore_unshareable_info"](o.method,n),r.version>=17){let s=Memory.alloc(2*V);s.writePointer(o.method),s.add(V).writePointer(n),r["Method::link_method"](o.method,s,n)}return o}function Lo(t){let e=Io(),n=t.add(e.method.constMethodOffset).readPointer(),r=n.add(e.constMethod.sizeOffset).readS32()*V,o=Memory.alloc(r+e.method.size);Memory.copy(o,n,r);let i=o.add(r);Memory.copy(i,t,e.method.size);let s=wo(i,o,r),c=wo(t,n,r);return s.oldMethod=c,s}function wo(t,e,n){let r=Ce(),o=Io(),i=t.add(o.method.constMethodOffset),s=t.add(o.method.methodDataOffset),c=t.add(o.method.methodCountersOffset),a=t.add(o.method.accessFlagsOffset),l=a.readU32(),d=o.getAdapterPointer(t,e),p=t.add(o.method.i2iEntryOffset),f=t.add(o.method.signatureHandlerOffset),u=e.add(o.constMethod.constantPoolOffset).readPointer(),_=e.add(o.constMethod.stackmapDataOffset),h=u.add(o.constantPool.instanceKlassOffset).readPointer(),g=u.add(o.constantPool.cacheOffset).readPointer(),b=Hl(),E=h.add(b.methodsOffset).readPointer(),C=E.readS32(),A=E.add(V),M=e.add(o.constMethod.methodIdnumOffset).readU16(),O=t.add(o.method.vtableIndexOffset),k=O.readS32(),N=h.add(b.vtableOffset),S=h.add(b.oopMapCacheOffset).readPointer(),L=r.version>=10?h.add(b.memberNamesOffset).readPointer():NULL;return{method:t,methodSize:o.method.size,const:e,constSize:n,constPtr:i,dataPtr:s,countersPtr:c,stackmapPtr:_,instanceKlass:h,methodsArray:A,methodsCount:C,methodIndex:M,vtableIndex:k,vtableIndexPtr:O,vtable:N,accessFlags:l,accessFlagsPtr:a,adapter:d,i2iEntry:p,signatureHandler:f,memberNames:L,cache:g,oopMapCache:S}}function rd(t){let{oldMethod:e}=t;e.accessFlagsPtr.writeU32(e.accessFlags),e.vtableIndexPtr.writeS32(e.vtableIndex)}function od(){let t=Ce(),{version:e}=t,n;e>=17?n="method:early":e>=9&&e<=16?n="const-method":n="method:late";let o=t["Method::size"](1)*V,i=V,s=2*V,c=3*V,a=4*V,l=n==="method:early"?V:0,d=a+l,p=d+4,f=p+4+8,u=f+V,_=l!==0?a:u,h=o-2*V,g=o-V,b=8,E=b+V,C=E+V,A=n==="const-method"?V:0,M=C+A,O=M+14,k=2*V,N=3*V;return{getAdapterPointer:A!==0?function(L,j){return j.add(C)}:function(L,j){return L.add(_)},method:{size:o,constMethodOffset:i,methodDataOffset:s,methodCountersOffset:c,accessFlagsOffset:d,vtableIndexOffset:p,i2iEntryOffset:f,nativeFunctionOffset:h,signatureHandlerOffset:g},constMethod:{constantPoolOffset:b,stackmapDataOffset:E,sizeOffset:M,methodIdnumOffset:O},constantPool:{cacheOffset:k,instanceKlassOffset:N}}}var id={x64:ad};function sd(){let{version:t,createNewDefaultVtableIndices:e}=Ce(),n=id[Process.arch];if(n===void 0)throw new Error(`Missing vtable offset parser for ${Process.arch}`);let r=xe(e,n,{limit:32});if(r===null)throw new Error("Unable to deduce vtable offset");let o=t>=10&&t<=11||t>=15?17:18,i=r-7*V,s=r-17*V,c=r-o*V;return{vtableOffset:r,methodsOffset:i,memberNamesOffset:s,oopMapCacheOffset:c}}function ad(t){if(t.mnemonic!=="mov")return null;let e=t.operands[0];if(e.type!=="mem")return null;let{value:n}=e;if(n.scale!==1)return null;let{disp:r}=n;return r<256?null:r+16}var xo=J;try{dt()}catch{xo=Ce}var pt=xo;var cd=`#include <json-glib/json-glib.h>
#include <string.h>

#define kAccStatic 0x0008
#define kAccConstructor 0x00010000

typedef struct _Model Model;
typedef struct _EnumerateMethodsContext EnumerateMethodsContext;

typedef struct _JavaApi JavaApi;
typedef struct _JavaClassApi JavaClassApi;
typedef struct _JavaMethodApi JavaMethodApi;
typedef struct _JavaFieldApi JavaFieldApi;

typedef struct _JNIEnv JNIEnv;
typedef guint8 jboolean;
typedef gint32 jint;
typedef jint jsize;
typedef gpointer jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray jobjectArray;
typedef gpointer jfieldID;
typedef gpointer jmethodID;

typedef struct _jvmtiEnv jvmtiEnv;
typedef enum
{
  JVMTI_ERROR_NONE = 0
} jvmtiError;

typedef struct _ArtApi ArtApi;
typedef guint32 ArtHeapReference;
typedef struct _ArtObject ArtObject;
typedef struct _ArtClass ArtClass;
typedef struct _ArtClassLinker ArtClassLinker;
typedef struct _ArtClassVisitor ArtClassVisitor;
typedef struct _ArtClassVisitorVTable ArtClassVisitorVTable;
typedef struct _ArtMethod ArtMethod;
typedef struct _ArtString ArtString;

typedef union _StdString StdString;
typedef struct _StdStringShort StdStringShort;
typedef struct _StdStringLong StdStringLong;

typedef void (* ArtVisitClassesFunc) (ArtClassLinker * linker, ArtClassVisitor * visitor);
typedef const char * (* ArtGetClassDescriptorFunc) (ArtClass * klass, StdString * storage);
typedef void (* ArtPrettyMethodFunc) (StdString * result, ArtMethod * method, jboolean with_signature);

struct _Model
{
  GHashTable * members;
};

struct _EnumerateMethodsContext
{
  GPatternSpec * class_query;
  GPatternSpec * method_query;
  jboolean include_signature;
  jboolean ignore_case;
  jboolean skip_system_classes;
  GHashTable * groups;
};

struct _JavaClassApi
{
  jmethodID get_declared_methods;
  jmethodID get_declared_fields;
};

struct _JavaMethodApi
{
  jmethodID get_name;
  jmethodID get_modifiers;
};

struct _JavaFieldApi
{
  jmethodID get_name;
  jmethodID get_modifiers;
};

struct _JavaApi
{
  jvmtiEnv * jvmti;
  JavaClassApi clazz;
  JavaMethodApi method;
  JavaFieldApi field;
};

struct _JNIEnv
{
  gpointer * functions;
};

struct _jvmtiEnv
{
  gpointer * functions;
};

struct _ArtApi
{
  gboolean available;

  guint class_offset_ifields;
  guint class_offset_methods;
  guint class_offset_sfields;
  guint class_offset_copied_methods_offset;

  guint method_size;
  guint method_offset_access_flags;

  guint field_size;
  guint field_offset_access_flags;

  guint alignment_padding;

  ArtClassLinker * linker;
  ArtVisitClassesFunc visit_classes;
  ArtGetClassDescriptorFunc get_class_descriptor;
  ArtPrettyMethodFunc pretty_method;

  void (* free) (gpointer mem);
};

struct _ArtObject
{
  ArtHeapReference klass;
  ArtHeapReference monitor;
};

struct _ArtClass
{
  ArtObject parent;

  ArtHeapReference class_loader;
};

struct _ArtClassVisitor
{
  ArtClassVisitorVTable * vtable;
  gpointer user_data;
};

struct _ArtClassVisitorVTable
{
  void (* reserved1) (ArtClassVisitor * self);
  void (* reserved2) (ArtClassVisitor * self);
  jboolean (* visit) (ArtClassVisitor * self, ArtClass * klass);
};

struct _ArtString
{
  ArtObject parent;

  gint32 count;
  guint32 hash_code;

  union
  {
    guint16 value[0];
    guint8 value_compressed[0];
  };
};

struct _StdStringShort
{
  guint8 size;
  gchar data[(3 * sizeof (gpointer)) - sizeof (guint8)];
};

struct _StdStringLong
{
  gsize capacity;
  gsize size;
  gchar * data;
};

union _StdString
{
  StdStringShort s;
  StdStringLong l;
};

static void model_add_method (Model * self, const gchar * name, jmethodID id, jint modifiers);
static void model_add_field (Model * self, const gchar * name, jfieldID id, jint modifiers);
static void model_free (Model * model);

static jboolean collect_matching_class_methods (ArtClassVisitor * self, ArtClass * klass);
static gchar * finalize_method_groups_to_json (GHashTable * groups);
static GPatternSpec * make_pattern_spec (const gchar * pattern, jboolean ignore_case);
static gchar * class_name_from_signature (const gchar * signature);
static gchar * format_method_signature (const gchar * name, const gchar * signature);
static void append_type (GString * output, const gchar ** type);

static gpointer read_art_array (gpointer object_base, guint field_offset, guint length_size, guint * length);

static void std_string_destroy (StdString * str);
static gchar * std_string_c_str (StdString * self);

extern GMutex lock;
extern GArray * models;
extern JavaApi java_api;
extern ArtApi art_api;

void
init (void)
{
  g_mutex_init (&lock);
  models = g_array_new (FALSE, FALSE, sizeof (Model *));
}

void
finalize (void)
{
  guint n, i;

  n = models->len;
  for (i = 0; i != n; i++)
  {
    Model * model = g_array_index (models, Model *, i);
    model_free (model);
  }

  g_array_unref (models);
  g_mutex_clear (&lock);
}

Model *
model_new (jclass class_handle,
           gpointer class_object,
           JNIEnv * env)
{
  Model * model;
  GHashTable * members;
  jvmtiEnv * jvmti = java_api.jvmti;
  gpointer * funcs = env->functions;
  jmethodID (* from_reflected_method) (JNIEnv *, jobject) = funcs[7];
  jfieldID (* from_reflected_field) (JNIEnv *, jobject) = funcs[8];
  jobject (* to_reflected_method) (JNIEnv *, jclass, jmethodID, jboolean) = funcs[9];
  jobject (* to_reflected_field) (JNIEnv *, jclass, jfieldID, jboolean) = funcs[12];
  void (* delete_local_ref) (JNIEnv *, jobject) = funcs[23];
  jobject (* call_object_method) (JNIEnv *, jobject, jmethodID, ...) = funcs[34];
  jint (* call_int_method) (JNIEnv *, jobject, jmethodID, ...) = funcs[49];
  const char * (* get_string_utf_chars) (JNIEnv *, jstring, jboolean *) = funcs[169];
  void (* release_string_utf_chars) (JNIEnv *, jstring, const char *) = funcs[170];
  jsize (* get_array_length) (JNIEnv *, jarray) = funcs[171];
  jobject (* get_object_array_element) (JNIEnv *, jobjectArray, jsize) = funcs[173];
  jsize n, i;

  model = g_new (Model, 1);

  members = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, g_free);
  model->members = members;

  if (jvmti != NULL)
  {
    gpointer * jf = jvmti->functions - 1;
    jvmtiError (* deallocate) (jvmtiEnv *, void * mem) = jf[47];
    jvmtiError (* get_class_methods) (jvmtiEnv *, jclass, jint *, jmethodID **) = jf[52];
    jvmtiError (* get_class_fields) (jvmtiEnv *, jclass, jint *, jfieldID **) = jf[53];
    jvmtiError (* get_field_name) (jvmtiEnv *, jclass, jfieldID, char **, char **, char **) = jf[60];
    jvmtiError (* get_field_modifiers) (jvmtiEnv *, jclass, jfieldID, jint *) = jf[62];
    jvmtiError (* get_method_name) (jvmtiEnv *, jmethodID, char **, char **, char **) = jf[64];
    jvmtiError (* get_method_modifiers) (jvmtiEnv *, jmethodID, jint *) = jf[66];
    jint method_count;
    jmethodID * methods;
    jint field_count;
    jfieldID * fields;
    char * name;
    jint modifiers;

    get_class_methods (jvmti, class_handle, &method_count, &methods);
    for (i = 0; i != method_count; i++)
    {
      jmethodID method = methods[i];

      get_method_name (jvmti, method, &name, NULL, NULL);
      get_method_modifiers (jvmti, method, &modifiers);

      model_add_method (model, name, method, modifiers);

      deallocate (jvmti, name);
    }
    deallocate (jvmti, methods);

    get_class_fields (jvmti, class_handle, &field_count, &fields);
    for (i = 0; i != field_count; i++)
    {
      jfieldID field = fields[i];

      get_field_name (jvmti, class_handle, field, &name, NULL, NULL);
      get_field_modifiers (jvmti, class_handle, field, &modifiers);

      model_add_field (model, name, field, modifiers);

      deallocate (jvmti, name);
    }
    deallocate (jvmti, fields);
  }
  else if (art_api.available)
  {
    gpointer elements;
    guint n, i;
    const guint field_arrays[] = {
      art_api.class_offset_ifields,
      art_api.class_offset_sfields
    };
    guint field_array_cursor;
    gboolean merged_fields = art_api.class_offset_sfields == 0;

    elements = read_art_array (class_object, art_api.class_offset_methods, sizeof (gsize), NULL);
    n = *(guint16 *) (class_object + art_api.class_offset_copied_methods_offset);
    for (i = 0; i != n; i++)
    {
      jmethodID id;
      guint32 access_flags;
      jboolean is_static;
      jobject method, name;
      const char * name_str;
      jint modifiers;

      id = elements + (i * art_api.method_size);

      access_flags = *(guint32 *) (id + art_api.method_offset_access_flags);
      if ((access_flags & kAccConstructor) != 0)
        continue;
      is_static = (access_flags & kAccStatic) != 0;
      method = to_reflected_method (env, class_handle, id, is_static);
      name = call_object_method (env, method, java_api.method.get_name);
      name_str = get_string_utf_chars (env, name, NULL);
      modifiers = access_flags & 0xffff;

      model_add_method (model, name_str, id, modifiers);

      release_string_utf_chars (env, name, name_str);
      delete_local_ref (env, name);
      delete_local_ref (env, method);
    }

    for (field_array_cursor = 0; field_array_cursor != G_N_ELEMENTS (field_arrays); field_array_cursor++)
    {
      jboolean is_static;

      if (field_arrays[field_array_cursor] == 0)
        continue;

      if (!merged_fields)
        is_static = field_array_cursor == 1;

      elements = read_art_array (class_object, field_arrays[field_array_cursor], sizeof (guint32), &n);
      for (i = 0; i != n; i++)
      {
        jfieldID id;
        guint32 access_flags;
        jobject field, name;
        const char * name_str;
        jint modifiers;

        id = elements + (i * art_api.field_size);

        access_flags = *(guint32 *) (id + art_api.field_offset_access_flags);
        if (merged_fields)
          is_static = (access_flags & kAccStatic) != 0;
        field = to_reflected_field (env, class_handle, id, is_static);
        name = call_object_method (env, field, java_api.field.get_name);
        name_str = get_string_utf_chars (env, name, NULL);
        modifiers = access_flags & 0xffff;

        model_add_field (model, name_str, id, modifiers);

        release_string_utf_chars (env, name, name_str);
        delete_local_ref (env, name);
        delete_local_ref (env, field);
      }
    }
  }
  else
  {
    jobject elements;

    elements = call_object_method (env, class_handle, java_api.clazz.get_declared_methods);
    n = get_array_length (env, elements);
    for (i = 0; i != n; i++)
    {
      jobject method, name;
      const char * name_str;
      jmethodID id;
      jint modifiers;

      method = get_object_array_element (env, elements, i);
      name = call_object_method (env, method, java_api.method.get_name);
      name_str = get_string_utf_chars (env, name, NULL);
      id = from_reflected_method (env, method);
      modifiers = call_int_method (env, method, java_api.method.get_modifiers);

      model_add_method (model, name_str, id, modifiers);

      release_string_utf_chars (env, name, name_str);
      delete_local_ref (env, name);
      delete_local_ref (env, method);
    }
    delete_local_ref (env, elements);

    elements = call_object_method (env, class_handle, java_api.clazz.get_declared_fields);
    n = get_array_length (env, elements);
    for (i = 0; i != n; i++)
    {
      jobject field, name;
      const char * name_str;
      jfieldID id;
      jint modifiers;

      field = get_object_array_element (env, elements, i);
      name = call_object_method (env, field, java_api.field.get_name);
      name_str = get_string_utf_chars (env, name, NULL);
      id = from_reflected_field (env, field);
      modifiers = call_int_method (env, field, java_api.field.get_modifiers);

      model_add_field (model, name_str, id, modifiers);

      release_string_utf_chars (env, name, name_str);
      delete_local_ref (env, name);
      delete_local_ref (env, field);
    }
    delete_local_ref (env, elements);
  }

  g_mutex_lock (&lock);
  g_array_append_val (models, model);
  g_mutex_unlock (&lock);

  return model;
}

static void
model_add_method (Model * self,
                  const gchar * name,
                  jmethodID id,
                  jint modifiers)
{
  GHashTable * members = self->members;
  gchar * key, type;
  const gchar * value;

  if (name[0] == '$')
    key = g_strdup_printf ("_%s", name);
  else
    key = g_strdup (name);

  type = (modifiers & kAccStatic) != 0 ? 's' : 'i';

  value = g_hash_table_lookup (members, key);
  if (value == NULL)
    g_hash_table_insert (members, key, g_strdup_printf ("m:%c0x%zx", type, id));
  else
    g_hash_table_insert (members, key, g_strdup_printf ("%s:%c0x%zx", value, type, id));
}

static void
model_add_field (Model * self,
                 const gchar * name,
                 jfieldID id,
                 jint modifiers)
{
  GHashTable * members = self->members;
  gchar * key, type;

  if (name[0] == '$')
    key = g_strdup_printf ("_%s", name);
  else
    key = g_strdup (name);
  while (g_hash_table_contains (members, key))
  {
    gchar * new_key = g_strdup_printf ("_%s", key);
    g_free (key);
    key = new_key;
  }

  type = (modifiers & kAccStatic) != 0 ? 's' : 'i';

  g_hash_table_insert (members, key, g_strdup_printf ("f:%c0x%zx", type, id));
}

static void
model_free (Model * model)
{
  g_hash_table_unref (model->members);

  g_free (model);
}

gboolean
model_has (Model * self,
           const gchar * member)
{
  return g_hash_table_contains (self->members, member);
}

const gchar *
model_find (Model * self,
            const gchar * member)
{
  return g_hash_table_lookup (self->members, member);
}

gchar *
model_list (Model * self)
{
  GString * result;
  GHashTableIter iter;
  guint i;
  const gchar * name;

  result = g_string_sized_new (128);

  g_string_append_c (result, '[');

  g_hash_table_iter_init (&iter, self->members);
  for (i = 0; g_hash_table_iter_next (&iter, (gpointer *) &name, NULL); i++)
  {
    if (i > 0)
      g_string_append_c (result, ',');

    g_string_append_c (result, '"');
    g_string_append (result, name);
    g_string_append_c (result, '"');
  }

  g_string_append_c (result, ']');

  return g_string_free (result, FALSE);
}

gchar *
enumerate_methods_art (const gchar * class_query,
                       const gchar * method_query,
                       jboolean include_signature,
                       jboolean ignore_case,
                       jboolean skip_system_classes)
{
  gchar * result;
  EnumerateMethodsContext ctx;
  ArtClassVisitor visitor;
  ArtClassVisitorVTable visitor_vtable = { NULL, };

  ctx.class_query = make_pattern_spec (class_query, ignore_case);
  ctx.method_query = make_pattern_spec (method_query, ignore_case);
  ctx.include_signature = include_signature;
  ctx.ignore_case = ignore_case;
  ctx.skip_system_classes = skip_system_classes;
  ctx.groups = g_hash_table_new_full (NULL, NULL, NULL, NULL);

  visitor.vtable = &visitor_vtable;
  visitor.user_data = &ctx;

  visitor_vtable.visit = collect_matching_class_methods;

  art_api.visit_classes (art_api.linker, &visitor);

  result = finalize_method_groups_to_json (ctx.groups);

  g_hash_table_unref (ctx.groups);
  g_pattern_spec_free (ctx.method_query);
  g_pattern_spec_free (ctx.class_query);

  return result;
}

static jboolean
collect_matching_class_methods (ArtClassVisitor * self,
                                ArtClass * klass)
{
  EnumerateMethodsContext * ctx = self->user_data;
  const char * descriptor;
  StdString descriptor_storage = { 0, };
  gchar * class_name = NULL;
  gchar * class_name_copy = NULL;
  const gchar * normalized_class_name;
  JsonBuilder * group;
  size_t class_name_length;
  GHashTable * seen_method_names;
  gpointer elements;
  guint n, i;

  if (ctx->skip_system_classes && klass->class_loader == 0)
    goto skip_class;

  descriptor = art_api.get_class_descriptor (klass, &descriptor_storage);
  if (descriptor[0] != 'L')
    goto skip_class;

  class_name = class_name_from_signature (descriptor);

  if (ctx->ignore_case)
  {
    class_name_copy = g_utf8_strdown (class_name, -1);
    normalized_class_name = class_name_copy;
  }
  else
  {
    normalized_class_name = class_name;
  }

  if (!g_pattern_match_string (ctx->class_query, normalized_class_name))
    goto skip_class;

  group = NULL;
  class_name_length = strlen (class_name);
  seen_method_names = ctx->include_signature ? NULL : g_hash_table_new_full (g_str_hash, g_str_equal, g_free, NULL);

  elements = read_art_array (klass, art_api.class_offset_methods, sizeof (gsize), NULL);
  n = *(guint16 *) ((gpointer) klass + art_api.class_offset_copied_methods_offset);
  for (i = 0; i != n; i++)
  {
    ArtMethod * method;
    guint32 access_flags;
    jboolean is_constructor;
    StdString method_name = { 0, };
    const gchar * bare_method_name;
    gchar * bare_method_name_copy = NULL;
    const gchar * normalized_method_name;
    gchar * normalized_method_name_copy = NULL;

    method = elements + (i * art_api.method_size);

    access_flags = *(guint32 *) ((gpointer) method + art_api.method_offset_access_flags);
    is_constructor = (access_flags & kAccConstructor) != 0;

    art_api.pretty_method (&method_name, method, ctx->include_signature);
    bare_method_name = std_string_c_str (&method_name);
    if (ctx->include_signature)
    {
      const gchar * return_type_end, * name_begin;
      GString * name;

      return_type_end = strchr (bare_method_name, ' ');
      name_begin = return_type_end + 1 + class_name_length + 1;
      if (is_constructor && g_str_has_prefix (name_begin, "<clinit>"))
        goto skip_method;

      name = g_string_sized_new (64);

      if (is_constructor)
      {
        g_string_append (name, "$init");
        g_string_append (name, strchr (name_begin, '>') + 1);
      }
      else
      {
        g_string_append (name, name_begin);
      }
      g_string_append (name, ": ");
      g_string_append_len (name, bare_method_name, return_type_end - bare_method_name);

      bare_method_name_copy = g_string_free (name, FALSE);
      bare_method_name = bare_method_name_copy;
    }
    else
    {
      const gchar * name_begin;

      name_begin = bare_method_name + class_name_length + 1;
      if (is_constructor && strcmp (name_begin, "<clinit>") == 0)
        goto skip_method;

      if (is_constructor)
        bare_method_name = "$init";
      else
        bare_method_name += class_name_length + 1;
    }

    if (seen_method_names != NULL && g_hash_table_contains (seen_method_names, bare_method_name))
      goto skip_method;

    if (ctx->ignore_case)
    {
      normalized_method_name_copy = g_utf8_strdown (bare_method_name, -1);
      normalized_method_name = normalized_method_name_copy;
    }
    else
    {
      normalized_method_name = bare_method_name;
    }

    if (!g_pattern_match_string (ctx->method_query, normalized_method_name))
      goto skip_method;

    if (group == NULL)
    {
      group = g_hash_table_lookup (ctx->groups, GUINT_TO_POINTER (klass->class_loader));
      if (group == NULL)
      {
        group = json_builder_new_immutable ();
        g_hash_table_insert (ctx->groups, GUINT_TO_POINTER (klass->class_loader), group);

        json_builder_begin_object (group);

        json_builder_set_member_name (group, "loader");
        json_builder_add_int_value (group, klass->class_loader);

        json_builder_set_member_name (group, "classes");
        json_builder_begin_array (group);
      }

      json_builder_begin_object (group);

      json_builder_set_member_name (group, "name");
      json_builder_add_string_value (group, class_name);

      json_builder_set_member_name (group, "methods");
      json_builder_begin_array (group);
    }

    json_builder_add_string_value (group, bare_method_name);

    if (seen_method_names != NULL)
      g_hash_table_add (seen_method_names, g_strdup (bare_method_name));

skip_method:
    g_free (normalized_method_name_copy);
    g_free (bare_method_name_copy);
    std_string_destroy (&method_name);
  }

  if (seen_method_names != NULL)
    g_hash_table_unref (seen_method_names);

  if (group == NULL)
    goto skip_class;

  json_builder_end_array (group);
  json_builder_end_object (group);

skip_class:
  g_free (class_name_copy);
  g_free (class_name);
  std_string_destroy (&descriptor_storage);

  return TRUE;
}

gchar *
enumerate_methods_jvm (const gchar * class_query,
                       const gchar * method_query,
                       jboolean include_signature,
                       jboolean ignore_case,
                       jboolean skip_system_classes,
                       JNIEnv * env)
{
  gchar * result;
  GPatternSpec * class_pattern, * method_pattern;
  GHashTable * groups;
  gpointer * ef = env->functions;
  jobject (* new_global_ref) (JNIEnv *, jobject) = ef[21];
  void (* delete_local_ref) (JNIEnv *, jobject) = ef[23];
  jboolean (* is_same_object) (JNIEnv *, jobject, jobject) = ef[24];
  jvmtiEnv * jvmti = java_api.jvmti;
  gpointer * jf = jvmti->functions - 1;
  jvmtiError (* deallocate) (jvmtiEnv *, void * mem) = jf[47];
  jvmtiError (* get_class_signature) (jvmtiEnv *, jclass, char **, char **) = jf[48];
  jvmtiError (* get_class_methods) (jvmtiEnv *, jclass, jint *, jmethodID **) = jf[52];
  jvmtiError (* get_class_loader) (jvmtiEnv *, jclass, jobject *) = jf[57];
  jvmtiError (* get_method_name) (jvmtiEnv *, jmethodID, char **, char **, char **) = jf[64];
  jvmtiError (* get_loaded_classes) (jvmtiEnv *, jint *, jclass **) = jf[78];
  jint class_count, class_index;
  jclass * classes;

  class_pattern = make_pattern_spec (class_query, ignore_case);
  method_pattern = make_pattern_spec (method_query, ignore_case);
  groups = g_hash_table_new_full (NULL, NULL, NULL, NULL);

  if (get_loaded_classes (jvmti, &class_count, &classes) != JVMTI_ERROR_NONE)
    goto emit_results;

  for (class_index = 0; class_index != class_count; class_index++)
  {
    jclass klass = classes[class_index];
    jobject loader = NULL;
    gboolean have_loader = FALSE;
    char * signature = NULL;
    gchar * class_name = NULL;
    gchar * class_name_copy = NULL;
    const gchar * normalized_class_name;
    jint method_count, method_index;
    jmethodID * methods = NULL;
    JsonBuilder * group = NULL;
    GHashTable * seen_method_names = NULL;

    if (skip_system_classes)
    {
      if (get_class_loader (jvmti, klass, &loader) != JVMTI_ERROR_NONE)
        goto skip_class;
      have_loader = TRUE;

      if (loader == NULL)
        goto skip_class;
    }

    if (get_class_signature (jvmti, klass, &signature, NULL) != JVMTI_ERROR_NONE)
      goto skip_class;

    class_name = class_name_from_signature (signature);

    if (ignore_case)
    {
      class_name_copy = g_utf8_strdown (class_name, -1);
      normalized_class_name = class_name_copy;
    }
    else
    {
      normalized_class_name = class_name;
    }

    if (!g_pattern_match_string (class_pattern, normalized_class_name))
      goto skip_class;

    if (get_class_methods (jvmti, klass, &method_count, &methods) != JVMTI_ERROR_NONE)
      goto skip_class;

    if (!include_signature)
      seen_method_names = g_hash_table_new_full (g_str_hash, g_str_equal, g_free, NULL);

    for (method_index = 0; method_index != method_count; method_index++)
    {
      jmethodID method = methods[method_index];
      const gchar * method_name;
      char * method_name_value = NULL;
      char * method_signature_value = NULL;
      gchar * method_name_copy = NULL;
      const gchar * normalized_method_name;
      gchar * normalized_method_name_copy = NULL;

      if (get_method_name (jvmti, method, &method_name_value, include_signature ? &method_signature_value : NULL, NULL) != JVMTI_ERROR_NONE)
        goto skip_method;
      method_name = method_name_value;

      if (method_name[0] == '<')
      {
        if (strcmp (method_name, "<init>") == 0)
          method_name = "$init";
        else if (strcmp (method_name, "<clinit>") == 0)
          goto skip_method;
      }

      if (include_signature)
      {
        method_name_copy = format_method_signature (method_name, method_signature_value);
        method_name = method_name_copy;
      }

      if (seen_method_names != NULL && g_hash_table_contains (seen_method_names, method_name))
        goto skip_method;

      if (ignore_case)
      {
        normalized_method_name_copy = g_utf8_strdown (method_name, -1);
        normalized_method_name = normalized_method_name_copy;
      }
      else
      {
        normalized_method_name = method_name;
      }

      if (!g_pattern_match_string (method_pattern, normalized_method_name))
        goto skip_method;

      if (group == NULL)
      {
        if (!have_loader && get_class_loader (jvmti, klass, &loader) != JVMTI_ERROR_NONE)
          goto skip_method;

        if (loader == NULL)
        {
          group = g_hash_table_lookup (groups, NULL);
        }
        else
        {
          GHashTableIter iter;
          jobject cur_loader;
          JsonBuilder * cur_group;

          g_hash_table_iter_init (&iter, groups);
          while (g_hash_table_iter_next (&iter, (gpointer *) &cur_loader, (gpointer *) &cur_group))
          {
            if (cur_loader != NULL && is_same_object (env, cur_loader, loader))
            {
              group = cur_group;
              break;
            }
          }
        }

        if (group == NULL)
        {
          jobject l;
          gchar * str;

          l = (loader != NULL) ? new_global_ref (env, loader) : NULL;

          group = json_builder_new_immutable ();
          g_hash_table_insert (groups, l, group);

          json_builder_begin_object (group);

          json_builder_set_member_name (group, "loader");
          str = g_strdup_printf ("0x%" G_GSIZE_MODIFIER "x", GPOINTER_TO_SIZE (l));
          json_builder_add_string_value (group, str);
          g_free (str);

          json_builder_set_member_name (group, "classes");
          json_builder_begin_array (group);
        }

        json_builder_begin_object (group);

        json_builder_set_member_name (group, "name");
        json_builder_add_string_value (group, class_name);

        json_builder_set_member_name (group, "methods");
        json_builder_begin_array (group);
      }

      json_builder_add_string_value (group, method_name);

      if (seen_method_names != NULL)
        g_hash_table_add (seen_method_names, g_strdup (method_name));

skip_method:
      g_free (normalized_method_name_copy);
      g_free (method_name_copy);
      deallocate (jvmti, method_signature_value);
      deallocate (jvmti, method_name_value);
    }

skip_class:
    if (group != NULL)
    {
      json_builder_end_array (group);
      json_builder_end_object (group);
    }

    if (seen_method_names != NULL)
      g_hash_table_unref (seen_method_names);

    deallocate (jvmti, methods);

    g_free (class_name_copy);
    g_free (class_name);
    deallocate (jvmti, signature);

    if (loader != NULL)
      delete_local_ref (env, loader);

    delete_local_ref (env, klass);
  }

  deallocate (jvmti, classes);

emit_results:
  result = finalize_method_groups_to_json (groups);

  g_hash_table_unref (groups);
  g_pattern_spec_free (method_pattern);
  g_pattern_spec_free (class_pattern);

  return result;
}

static gchar *
finalize_method_groups_to_json (GHashTable * groups)
{
  GString * result;
  GHashTableIter iter;
  guint i;
  JsonBuilder * group;

  result = g_string_sized_new (1024);

  g_string_append_c (result, '[');

  g_hash_table_iter_init (&iter, groups);
  for (i = 0; g_hash_table_iter_next (&iter, NULL, (gpointer *) &group); i++)
  {
    JsonNode * root;
    gchar * json;

    if (i > 0)
      g_string_append_c (result, ',');

    json_builder_end_array (group);
    json_builder_end_object (group);

    root = json_builder_get_root (group);
    json = json_to_string (root, FALSE);
    g_string_append (result, json);
    g_free (json);
    json_node_unref (root);

    g_object_unref (group);
  }

  g_string_append_c (result, ']');

  return g_string_free (result, FALSE);
}

static GPatternSpec *
make_pattern_spec (const gchar * pattern,
                   jboolean ignore_case)
{
  GPatternSpec * spec;

  if (ignore_case)
  {
    gchar * str = g_utf8_strdown (pattern, -1);
    spec = g_pattern_spec_new (str);
    g_free (str);
  }
  else
  {
    spec = g_pattern_spec_new (pattern);
  }

  return spec;
}

static gchar *
class_name_from_signature (const gchar * descriptor)
{
  gchar * result, * c;

  result = g_strdup (descriptor + 1);

  for (c = result; *c != '\\0'; c++)
  {
    if (*c == '/')
      *c = '.';
  }

  c[-1] = '\\0';

  return result;
}

static gchar *
format_method_signature (const gchar * name,
                         const gchar * signature)
{
  GString * sig;
  const gchar * cursor;
  gint arg_index;

  sig = g_string_sized_new (128);

  g_string_append (sig, name);

  cursor = signature;
  arg_index = -1;
  while (TRUE)
  {
    const gchar c = *cursor;

    if (c == '(')
    {
      g_string_append_c (sig, c);
      cursor++;
      arg_index = 0;
    }
    else if (c == ')')
    {
      g_string_append_c (sig, c);
      cursor++;
      break;
    }
    else
    {
      if (arg_index >= 1)
        g_string_append (sig, ", ");

      append_type (sig, &cursor);

      if (arg_index != -1)
        arg_index++;
    }
  }

  g_string_append (sig, ": ");
  append_type (sig, &cursor);

  return g_string_free (sig, FALSE);
}

static void
append_type (GString * output,
             const gchar ** type)
{
  const gchar * cursor = *type;

  switch (*cursor)
  {
    case 'Z':
      g_string_append (output, "boolean");
      cursor++;
      break;
    case 'B':
      g_string_append (output, "byte");
      cursor++;
      break;
    case 'C':
      g_string_append (output, "char");
      cursor++;
      break;
    case 'S':
      g_string_append (output, "short");
      cursor++;
      break;
    case 'I':
      g_string_append (output, "int");
      cursor++;
      break;
    case 'J':
      g_string_append (output, "long");
      cursor++;
      break;
    case 'F':
      g_string_append (output, "float");
      cursor++;
      break;
    case 'D':
      g_string_append (output, "double");
      cursor++;
      break;
    case 'V':
      g_string_append (output, "void");
      cursor++;
      break;
    case 'L':
    {
      gchar ch;

      cursor++;
      for (; (ch = *cursor) != ';'; cursor++)
      {
        g_string_append_c (output, (ch != '/') ? ch : '.');
      }
      cursor++;

      break;
    }
    case '[':
      *type = cursor + 1;
      append_type (output, type);
      g_string_append (output, "[]");
      return;
    default:
      g_string_append (output, "BUG");
      cursor++;
  }

  *type = cursor;
}

void
dealloc (gpointer mem)
{
  g_free (mem);
}

static gpointer
read_art_array (gpointer object_base,
                guint field_offset,
                guint length_size,
                guint * length)
{
  gpointer result, header;
  guint n;

  header = GSIZE_TO_POINTER (*(guint64 *) (object_base + field_offset));
  if (header != NULL)
  {
    result = header + length_size;
    if (length_size == sizeof (guint32))
      n = *(guint32 *) header;
    else
      n = *(guint64 *) header;
  }
  else
  {
    result = NULL;
    n = 0;
  }

  if (length != NULL)
    *length = n;

  return result;
}

static void
std_string_destroy (StdString * str)
{
  if ((str->l.capacity & 1) != 0)
    art_api.free (str->l.data);
}

static gchar *
std_string_c_str (StdString * self)
{
  if ((self->l.capacity & 1) != 0)
    return self->l.data;

  return self->s.data;
}
`,ld=/(.+)!([^/]+)\/?([isu]+)?/,Ee=null,ko=null,Ue=class t{static build(e,n){return No(n),ko(e,n,r=>new t(Ee.new(e,r,n)))}static enumerateMethods(e,n,r){No(r);let o=e.match(ld);if(o===null)throw new Error("Invalid query; format is: class!method -- see documentation of Java.enumerateMethods(query) for details");let i=Memory.allocUtf8String(o[1]),s=Memory.allocUtf8String(o[2]),c=!1,a=!1,l=!1,d=o[3];d!==void 0&&(c=d.indexOf("s")!==-1,a=d.indexOf("i")!==-1,l=d.indexOf("u")!==-1);let p;if(n.jvmti!==null){let f=Ee.enumerateMethodsJvm(i,s,qe(c),qe(a),qe(l),r);try{p=JSON.parse(f.readUtf8String()).map(u=>{let _=ptr(u.loader);return u.loader=_.isNull()?null:_,u})}finally{Ee.dealloc(f)}}else ye(r.vm,r,f=>{let u=Ee.enumerateMethodsArt(i,s,qe(c),qe(a),qe(l));try{let _=n["art::JavaVMExt::AddGlobalRef"],{vm:h}=n;p=JSON.parse(u.readUtf8String()).map(g=>{let b=g.loader;return g.loader=b!==0?_(h,f,ptr(b)):null,g})}finally{Ee.dealloc(u)}});return p}constructor(e){this.handle=e}has(e){return Ee.has(this.handle,Memory.allocUtf8String(e))!==0}find(e){return Ee.find(this.handle,Memory.allocUtf8String(e)).readUtf8String()}list(){let e=Ee.list(this.handle);try{return JSON.parse(e.readUtf8String())}finally{Ee.dealloc(e)}}};function No(t){Ee===null&&(Ee=dd(t),ko=ud(Ee,t.vm))}function dd(t){let e=pt(),{jvmti:n=null}=e,{pointerSize:r}=Process,o=8,i=r,s=7*r,c=10*4+5*r,a=o+i+s+c,d=Memory.alloc(a),p=d.add(o),f=p.add(i),{getDeclaredMethods:u,getDeclaredFields:_}=t.javaLangClass(),h=t.javaLangReflectMethod(),g=t.javaLangReflectField(),b=f;[n!==null?n:NULL,u,_,h.getName,h.getModifiers,g.getName,g.getModifiers].forEach(k=>{b=b.writePointer(k).add(r)});let E=f.add(s),{vm:C}=t;if(e.flavor==="art"){let k;if(n!==null)k=[0,0,0,0];else{let j=Vn(C).offset;k=[j.ifields,j.methods,j.sfields,j.copiedMethodsOffset]}let N=_e(C),S=Rt(C),L=E;[1,...k,N.size,N.offset.accessFlags,S.size,S.offset.accessFlags,4294967295].forEach(j=>{L=L.writeUInt(j).add(4)}),[e.artClassLinker.address,e["art::ClassLinker::VisitClasses"],e["art::mirror::Class::GetDescriptor"],e["art::ArtMethod::PrettyMethod"],Process.getModuleByName("libc.so").getExportByName("free")].forEach((j,w)=>{j===void 0&&(j=NULL),L=L.writePointer(j).add(r)})}let A=new CModule(cd,{lock:d,models:p,java_api:f,art_api:E}),M={exceptions:"propagate"},O={exceptions:"propagate",scheduling:"exclusive"};return{handle:A,new:new NativeFunction(A.model_new,"pointer",["pointer","pointer","pointer"],M),has:new NativeFunction(A.model_has,"bool",["pointer","pointer"],O),find:new NativeFunction(A.model_find,"pointer",["pointer","pointer"],O),list:new NativeFunction(A.model_list,"pointer",["pointer"],O),enumerateMethodsArt:new NativeFunction(A.enumerate_methods_art,"pointer",["pointer","pointer","bool","bool","bool"],M),enumerateMethodsJvm:new NativeFunction(A.enumerate_methods_jvm,"pointer",["pointer","pointer","bool","bool","bool","pointer"],M),dealloc:new NativeFunction(A.dealloc,"void",["pointer"],O)}}function ud(t,e){let n=pt();if(n.flavor!=="art")return pd;let r=n["art::JavaVMExt::DecodeGlobal"];return function(o,i,s){let c;return ye(e,i,a=>{let l=r(e,a,o);c=s(l)}),c}}function pd(t,e,n){return n(NULL)}function qe(t){return t?1:0}var ft=class{constructor(e,n){this.items=new Map,this.capacity=e,this.destroy=n}dispose(e){let{items:n,destroy:r}=this;n.forEach(o=>{r(o,e)}),n.clear()}get(e){let{items:n}=this,r=n.get(e);return r!==void 0&&(n.delete(e),n.set(e,r)),r}set(e,n,r){let{items:o}=this,i=o.get(e);if(i!==void 0)o.delete(e),this.destroy(i,r);else if(o.size===this.capacity){let s=o.keys().next().value,c=o.get(s);o.delete(s),this.destroy(c,r)}o.set(e,n)}};var ht=1,sr=256,Mo=65536,fd=305419896,jo=32,Oo=12,Ro=8,Po=8,Fo=4,Do=4,Uo=12,hd=0,_d=1,md=2,gd=3,bd=4,yd=5,Ed=6,vd=4096,Sd=4097,wd=4099,Id=8192,Cd=8193,Td=8194,Ad=8195,Ld=8196,xd=8198,Nd=24,kd=28,Md=2,jd=24,Bo=m.from([3,0,7,14,0]),or="Ldalvik/annotation/Throws;",Od=m.from([0]);function Rd(t){let e=new ar,n=Object.assign({},t);return e.addClass(n),e.build()}var ar=class{constructor(){this.classes=[]}addClass(e){this.classes.push(e)}build(){let e=Dd(this.classes),{classes:n,interfaces:r,fields:o,methods:i,protos:s,parameters:c,annotationDirectories:a,annotationSets:l,throwsAnnotations:d,types:p,strings:f}=e,u=0,_=0,h=8,g=12,b=20,E=112;u+=E;let C=u,A=f.length*Do;u+=A;let M=u,O=p.length*Fo;u+=O;let k=u,N=s.length*Oo;u+=N;let S=u,L=o.length*Ro;u+=L;let j=u,w=i.length*Po;u+=w;let R=u,D=n.length*jo;u+=D;let U=u,F=l.map(T=>{let P=u;return T.offset=P,u+=4+T.items.length*4,P}),z=n.reduce((T,P)=>(P.classData.constructorMethods.forEach(H=>{let[,q,Z]=H;(q&sr)===0&&Z>=0&&(H.push(u),T.push({offset:u,superConstructor:Z}),u+=jd)}),T),[]);a.forEach(T=>{T.offset=u,u+=16+T.methods.length*8});let X=r.map(T=>{u=ir(u,4);let P=u;return T.offset=P,u+=4+2*T.types.length,P}),te=c.map(T=>{u=ir(u,4);let P=u;return T.offset=P,u+=4+2*T.types.length,P}),ae=[],Q=f.map(T=>{let P=u,B=m.from(me(T.length)),H=m.from(T,"utf8"),q=m.concat([B,H,Od]);return ae.push(q),u+=q.length,P}),ie=z.map(T=>{let P=u;return u+=Bo.length,P}),Y=d.map(T=>{let P=Fd(T);return T.offset=u,u+=P.length,P}),ne=n.map((T,P)=>{T.classData.offset=u;let B=Pd(T);return u+=B.length,B}),Se=0,Ye=0;u=ir(u,4);let $=u,he=r.length+c.length,Te=4+(o.length>0?1:0)+2+l.length+z.length+a.length+(he>0?1:0)+1+ie.length+d.length+n.length+1,Oe=4+Te*Uo;u+=Oe;let ke=u-U,Ve=u,I=m.alloc(Ve);I.write(`dex
035`),I.writeUInt32LE(Ve,32),I.writeUInt32LE(E,36),I.writeUInt32LE(fd,40),I.writeUInt32LE(Se,44),I.writeUInt32LE(Ye,48),I.writeUInt32LE($,52),I.writeUInt32LE(f.length,56),I.writeUInt32LE(C,60),I.writeUInt32LE(p.length,64),I.writeUInt32LE(M,68),I.writeUInt32LE(s.length,72),I.writeUInt32LE(k,76),I.writeUInt32LE(o.length,80),I.writeUInt32LE(o.length>0?S:0,84),I.writeUInt32LE(i.length,88),I.writeUInt32LE(j,92),I.writeUInt32LE(n.length,96),I.writeUInt32LE(R,100),I.writeUInt32LE(ke,104),I.writeUInt32LE(U,108),Q.forEach((T,P)=>{I.writeUInt32LE(T,C+P*Do)}),p.forEach((T,P)=>{I.writeUInt32LE(T,M+P*Fo)}),s.forEach((T,P)=>{let[B,H,q]=T,Z=k+P*Oo;I.writeUInt32LE(B,Z),I.writeUInt32LE(H,Z+4),I.writeUInt32LE(q!==null?q.offset:0,Z+8)}),o.forEach((T,P)=>{let[B,H,q]=T,Z=S+P*Ro;I.writeUInt16LE(B,Z),I.writeUInt16LE(H,Z+2),I.writeUInt32LE(q,Z+4)}),i.forEach((T,P)=>{let[B,H,q]=T,Z=j+P*Po;I.writeUInt16LE(B,Z),I.writeUInt16LE(H,Z+2),I.writeUInt32LE(q,Z+4)}),n.forEach((T,P)=>{let{interfaces:B,annotationsDirectory:H}=T,q=B!==null?B.offset:0,Z=H!==null?H.offset:0,Xe=0,ge=R+P*jo;I.writeUInt32LE(T.index,ge),I.writeUInt32LE(T.accessFlags,ge+4),I.writeUInt32LE(T.superClassIndex,ge+8),I.writeUInt32LE(q,ge+12),I.writeUInt32LE(T.sourceFileIndex,ge+16),I.writeUInt32LE(Z,ge+20),I.writeUInt32LE(T.classData.offset,ge+24),I.writeUInt32LE(Xe,ge+28)}),l.forEach((T,P)=>{let{items:B}=T,H=F[P];I.writeUInt32LE(B.length,H),B.forEach((q,Z)=>{I.writeUInt32LE(q.offset,H+4+Z*4)})}),z.forEach((T,P)=>{let{offset:B,superConstructor:H}=T,q=1,Z=1,Xe=1,ge=0,gt=4;I.writeUInt16LE(q,B),I.writeUInt16LE(Z,B+2),I.writeUInt16LE(Xe,B+4),I.writeUInt16LE(ge,B+6),I.writeUInt32LE(ie[P],B+8),I.writeUInt32LE(gt,B+12),I.writeUInt16LE(4208,B+16),I.writeUInt16LE(H,B+18),I.writeUInt16LE(0,B+20),I.writeUInt16LE(14,B+22)}),a.forEach(T=>{let P=T.offset,B=0,H=0,q=T.methods.length,Z=0;I.writeUInt32LE(B,P),I.writeUInt32LE(H,P+4),I.writeUInt32LE(q,P+8),I.writeUInt32LE(Z,P+12),T.methods.forEach((Xe,ge)=>{let gt=P+16+ge*8,[fi,hi]=Xe;I.writeUInt32LE(fi,gt),I.writeUInt32LE(hi.offset,gt+4)})}),r.forEach((T,P)=>{let B=X[P];I.writeUInt32LE(T.types.length,B),T.types.forEach((H,q)=>{I.writeUInt16LE(H,B+4+q*2)})}),c.forEach((T,P)=>{let B=te[P];I.writeUInt32LE(T.types.length,B),T.types.forEach((H,q)=>{I.writeUInt16LE(H,B+4+q*2)})}),ae.forEach((T,P)=>{T.copy(I,Q[P])}),ie.forEach(T=>{Bo.copy(I,T)}),Y.forEach((T,P)=>{T.copy(I,d[P].offset)}),ne.forEach((T,P)=>{T.copy(I,n[P].classData.offset)}),I.writeUInt32LE(Te,$);let ce=[[hd,1,_],[_d,f.length,C],[md,p.length,M],[gd,s.length,k]];o.length>0&&ce.push([bd,o.length,S]),ce.push([yd,i.length,j]),ce.push([Ed,n.length,R]),l.forEach((T,P)=>{ce.push([wd,T.items.length,F[P]])}),z.forEach(T=>{ce.push([Cd,1,T.offset])}),a.forEach(T=>{ce.push([xd,1,T.offset])}),he>0&&ce.push([Sd,he,X.concat(te)[0]]),ce.push([Td,f.length,Q[0]]),ie.forEach(T=>{ce.push([Ad,1,T])}),d.forEach(T=>{ce.push([Ld,1,T.offset])}),n.forEach(T=>{ce.push([Id,1,T.classData.offset])}),ce.push([vd,1,$]),ce.forEach((T,P)=>{let[B,H,q]=T,Z=$+4+P*Uo;I.writeUInt16LE(B,Z),I.writeUInt32LE(H,Z+4),I.writeUInt32LE(q,Z+8)});let wr=new Checksum("sha1");return wr.update(I.slice(g+b)),m.from(wr.getDigest()).copy(I,g),I.writeUInt32LE(Gd(I,g),h),I}};function Pd(t){let{instanceFields:e,constructorMethods:n,virtualMethods:r}=t.classData;return m.from([0].concat(me(e.length)).concat(me(n.length)).concat(me(r.length)).concat(e.reduce((i,[s,c])=>i.concat(me(s)).concat(me(c)),[])).concat(n.reduce((i,[s,c,,a])=>i.concat(me(s)).concat(me(c)).concat(me(a||0)),[])).concat(r.reduce((i,[s,c])=>i.concat(me(s)).concat(me(c)).concat([0]),[])))}function Fd(t){let{thrownTypes:e}=t;return m.from([Md].concat(me(t.type)).concat([1]).concat(me(t.value)).concat([kd,e.length]).concat(e.reduce((n,r)=>(n.push(Nd,r),n),[])))}function Dd(t){let e=new Set,n=new Set,r={},o=[],i=[],s={},c=new Set,a=new Set;t.forEach(w=>{let{name:R,superClass:D,sourceFileName:U}=w;e.add("this"),e.add(R),n.add(R),e.add(D),n.add(D),e.add(U),w.interfaces.forEach(F=>{e.add(F),n.add(F)}),w.fields.forEach(F=>{let[z,X]=F;e.add(z),e.add(X),n.add(X),o.push([w.name,X,z])}),w.methods.some(([F])=>F==="<init>")||(w.methods.unshift(["<init>","V",[]]),c.add(R)),w.methods.forEach(F=>{let[z,X,te,ae=[],Q]=F;e.add(z);let ie=l(X,te),Y=null;if(ae.length>0){let ne=ae.slice();ne.sort(),Y=ne.join("|");let Se=s[Y];Se===void 0&&(Se={id:Y,types:ne},s[Y]=Se),e.add(or),n.add(or),ae.forEach(Ye=>{e.add(Ye),n.add(Ye)}),e.add("value")}if(i.push([w.name,ie,z,Y,Q]),z==="<init>"){a.add(R+"|"+ie);let ne=D+"|"+ie;c.has(R)&&!a.has(ne)&&(i.push([D,ie,z,null,0]),a.add(ne))}})});function l(w,R){let D=[w].concat(R),U=D.join("|");if(r[U]!==void 0)return U;e.add(w),n.add(w),R.forEach(z=>{e.add(z),n.add(z)});let F=D.map(Jd).join("");return e.add(F),r[U]=[U,F,w,R],U}let d=Array.from(e);d.sort();let p=d.reduce((w,R,D)=>(w[R]=D,w),{}),f=Array.from(n).map(w=>p[w]);f.sort(zo);let u=f.reduce((w,R,D)=>(w[d[R]]=D,w),{}),_=Object.keys(r).map(w=>r[w]);_.sort(Bd);let h={},g=_.map(w=>{let[,R,D,U]=w,F;if(U.length>0){let z=U.join("|");F=h[z],F===void 0&&(F={types:U.map(X=>u[X]),offset:-1},h[z]=F)}else F=null;return[p[R],u[D],F]}),b=_.reduce((w,R,D)=>{let[U]=R;return w[U]=D,w},{}),E=Object.keys(h).map(w=>h[w]),C=o.map(w=>{let[R,D,U]=w;return[u[R],u[D],p[U]]});C.sort(zd);let A=i.map(w=>{let[R,D,U,F,z]=w;return[u[R],b[D],p[U],F,z]});A.sort(Vd);let M=Object.keys(s).map(w=>s[w]).map(w=>({id:w.id,type:u[or],value:p.value,thrownTypes:w.types.map(R=>u[R]),offset:-1})),O=M.map(w=>({id:w.id,items:[w],offset:-1})),k=O.reduce((w,R,D)=>(w[R.id]=D,w),{}),N={},S=[],L=t.map(w=>{let R=u[w.name],D=ht,U=u[w.superClass],F,z=w.interfaces.map($=>u[$]);if(z.length>0){z.sort(zo);let $=z.join("|");F=N[$],F===void 0&&(F={types:z,offset:-1},N[$]=F)}else F=null;let X=p[w.sourceFileName],te=A.reduce(($,he,Te)=>{let[Oe,ke,Ve,I,ce]=he;return Oe===R&&$.push([Te,Ve,I,ke,ce]),$},[]),ae=null,Q=te.filter(([,,$])=>$!==null).map(([$,,he])=>[$,O[k[he]]]);Q.length>0&&(ae={methods:Q,offset:-1},S.push(ae));let ie=C.reduce(($,he,Te)=>{let[Oe]=he;return Oe===R&&$.push([Te>0?1:0,ht]),$},[]),Y=p["<init>"],ne=te.filter(([,$])=>$===Y).map(([$,,,he])=>{if(c.has(w.name)){let Te=-1,Oe=A.length;for(let ke=0;ke!==Oe;ke++){let[Ve,I,ce]=A[ke];if(Ve===U&&ce===Y&&I===he){Te=ke;break}}return[$,ht|Mo,Te]}else return[$,ht|Mo|sr,-1]}),Se=Ud(te.filter(([,$])=>$!==Y).map(([$,,,,he])=>[$,he|ht|sr]));return{index:R,accessFlags:D,superClassIndex:U,interfaces:F,sourceFileIndex:X,annotationsDirectory:ae,classData:{instanceFields:ie,constructorMethods:ne,virtualMethods:Se,offset:-1}}}),j=Object.keys(N).map(w=>N[w]);return{classes:L,interfaces:j,fields:C,methods:A,protos:g,parameters:E,annotationDirectories:S,annotationSets:O,throwsAnnotations:M,types:f,strings:d}}function Ud(t){let e=0;return t.map(([n,r],o)=>{let i;return o===0?i=[n,r]:i=[n-e,r],e=n,i})}function zo(t,e){return t-e}function Bd(t,e){let[,,n,r]=t,[,,o,i]=e;if(n<o)return-1;if(n>o)return 1;let s=r.join("|"),c=i.join("|");return s<c?-1:s>c?1:0}function zd(t,e){let[n,r,o]=t,[i,s,c]=e;return n!==i?n-i:o!==c?o-c:r-s}function Vd(t,e){let[n,r,o]=t,[i,s,c]=e;return n!==i?n-i:o!==c?o-c:r-s}function Jd(t){let e=t[0];return e==="L"||e==="["?"L":t}function me(t){if(t<=127)return[t];let e=[],n=!1;do{let r=t&127;t>>=7,n=t!==0,n&&(r|=128),e.push(r)}while(n);return e}function ir(t,e){let n=t%e;return n===0?t:t+e-n}function Gd(t,e){let n=1,r=0,o=t.length;for(let i=e;i<o;i++)n=(n+t[i])%65521,r=(r+n)%65521;return(r<<16|n)>>>0}var Vo=Rd;var $d=1,cr=null,Jo=null;function Go(t){cr=t}function lr(t,e,n){let r=We(t);return r===null&&(t.indexOf("[")===0?r=dr(t,e,n):(t[0]==="L"&&t[t.length-1]===";"&&(t=t.substring(1,t.length-1)),r=Zd(t,e,n))),Object.assign({className:t},r)}var $o={boolean:{name:"Z",type:"uint8",size:1,byteSize:1,defaultValue:!1,isCompatible(t){return typeof t=="boolean"},fromJni(t){return!!t},toJni(t){return t?1:0},read(t){return t.readU8()},write(t,e){t.writeU8(e)},toString(){return this.name}},byte:{name:"B",type:"int8",size:1,byteSize:1,defaultValue:0,isCompatible(t){return Number.isInteger(t)&&t>=-128&&t<=127},fromJni:ve,toJni:ve,read(t){return t.readS8()},write(t,e){t.writeS8(e)},toString(){return this.name}},char:{name:"C",type:"uint16",size:1,byteSize:2,defaultValue:0,isCompatible(t){if(typeof t!="string"||t.length!==1)return!1;let e=t.charCodeAt(0);return e>=0&&e<=65535},fromJni(t){return String.fromCharCode(t)},toJni(t){return t.charCodeAt(0)},read(t){return t.readU16()},write(t,e){t.writeU16(e)},toString(){return this.name}},short:{name:"S",type:"int16",size:1,byteSize:2,defaultValue:0,isCompatible(t){return Number.isInteger(t)&&t>=-32768&&t<=32767},fromJni:ve,toJni:ve,read(t){return t.readS16()},write(t,e){t.writeS16(e)},toString(){return this.name}},int:{name:"I",type:"int32",size:1,byteSize:4,defaultValue:0,isCompatible(t){return Number.isInteger(t)&&t>=-2147483648&&t<=2147483647},fromJni:ve,toJni:ve,read(t){return t.readS32()},write(t,e){t.writeS32(e)},toString(){return this.name}},long:{name:"J",type:"int64",size:2,byteSize:8,defaultValue:0,isCompatible(t){return typeof t=="number"||t instanceof Int64},fromJni:ve,toJni:ve,read(t){return t.readS64()},write(t,e){t.writeS64(e)},toString(){return this.name}},float:{name:"F",type:"float",size:1,byteSize:4,defaultValue:0,isCompatible(t){return typeof t=="number"},fromJni:ve,toJni:ve,read(t){return t.readFloat()},write(t,e){t.writeFloat(e)},toString(){return this.name}},double:{name:"D",type:"double",size:2,byteSize:8,defaultValue:0,isCompatible(t){return typeof t=="number"},fromJni:ve,toJni:ve,read(t){return t.readDouble()},write(t,e){t.writeDouble(e)},toString(){return this.name}},void:{name:"V",type:"void",size:0,byteSize:0,defaultValue:void 0,isCompatible(t){return t===void 0},fromJni(){},toJni(){return NULL},toString(){return this.name}}},Hd=new Set(Object.values($o).map(t=>t.name));function We(t){let e=$o[t];return e!==void 0?e:null}function Zd(t,e,n){let r=n._types[e?1:0],o=r[t];return o!==void 0||(t==="java.lang.Object"?o=qd(n):o=Wd(t,e,n),r[t]=o),o}function qd(t){return{name:"Ljava/lang/Object;",type:"pointer",size:1,defaultValue:NULL,isCompatible(e){return e===null?!0:e===void 0?!1:e.$h instanceof NativePointer?!0:typeof e=="string"},fromJni(e,n,r){return e.isNull()?null:t.cast(e,t.use("java.lang.Object"),r)},toJni(e,n){return e===null?NULL:typeof e=="string"?n.newStringUtf(e):e.$h}}}function Wd(t,e,n){let r=null,o=null,i=null;function s(){return r===null&&(r=n.use(t).class),r}function c(l){let d=s();return o===null&&(o=d.isInstance.overload("java.lang.Object")),o.call(d,l)}function a(){if(i===null){let l=s();i=n.use("java.lang.String").class.isAssignableFrom(l)}return i}return{name:Be(t),type:"pointer",size:1,defaultValue:NULL,isCompatible(l){return l===null?!0:l===void 0?!1:l.$h instanceof NativePointer?c(l):typeof l=="string"&&a()},fromJni(l,d,p){return l.isNull()?null:a()&&e?d.stringFromJni(l):n.cast(l,n.use(t),p)},toJni(l,d){return l===null?NULL:typeof l=="string"?d.newStringUtf(l):l.$h},toString(){return this.name}}}var Kd=[["Z","boolean"],["B","byte"],["C","char"],["D","double"],["F","float"],["I","int"],["J","long"],["S","short"]].reduce((t,[e,n])=>(t["["+e]=Qd("["+e,n),t),{});function Qd(t,e){let n=y.prototype,r=nu(e),o={typeName:e,newArray:n["new"+r+"Array"],setRegion:n["set"+r+"ArrayRegion"],getElements:n["get"+r+"ArrayElements"],releaseElements:n["release"+r+"ArrayElements"]};return{name:t,type:"pointer",size:1,defaultValue:NULL,isCompatible(i){return tu(i,e)},fromJni(i,s,c){return Xd(i,o,s,c)},toJni(i,s){return eu(i,o,s)}}}function dr(t,e,n){let r=Kd[t];if(r!==void 0)return r;if(t.indexOf("[")!==0)throw new Error("Unsupported type: "+t);let o=t.substring(1),i=lr(o,e,n),s=0,c=o.length;for(;s!==c&&o[s]==="[";)s++;o=o.substring(s),o[0]==="L"&&o[o.length-1]===";"&&(o=o.substring(1,o.length-1));let a=o.replace(/\./g,"/");Hd.has(a)?a="[".repeat(s)+a:a="[".repeat(s)+"L"+a+";";let l="["+a;return o="[".repeat(s)+o,{name:t.replace(/\./g,"/"),type:"pointer",size:1,defaultValue:NULL,isCompatible(d){return d===null?!0:typeof d!="object"||d.length===void 0?!1:d.every(function(p){return i.isCompatible(p)})},fromJni(d,p,f){if(d.isNull())return null;let u=[],_=p.getArrayLength(d);for(let h=0;h!==_;h++){let g=p.getObjectArrayElement(d,h);try{u.push(i.fromJni(g,p))}finally{p.deleteLocalRef(g)}}try{u.$w=n.cast(d,n.use(l),f)}catch{n.use("java.lang.reflect.Array").newInstance(n.use(o).class,0),u.$w=n.cast(d,n.use(l),f)}return u.$dispose=Yd,u},toJni(d,p){if(d===null)return NULL;if(!(d instanceof Array))throw new Error("Expected an array");let f=d.$w;if(f!==void 0)return f.$h;let u=d.length,h=n.use(o).$borrowClassHandle(p);try{let g=p.newObjectArray(u,h.value,NULL);p.throwIfExceptionPending();for(let b=0;b!==u;b++){let E=i.toJni(d[b],p);try{p.setObjectArrayElement(g,b,E)}finally{i.type==="pointer"&&p.getObjectRefType(E)===$d&&p.deleteLocalRef(E)}p.throwIfExceptionPending()}return g}finally{h.unref(p)}}}}function Yd(){let t=this.length;for(let e=0;e!==t;e++){let n=this[e];if(n===null)continue;let r=n.$dispose;if(r===void 0)break;r.call(n)}this.$w.$dispose()}function Xd(t,e,n,r){if(t.isNull())return null;let o=We(e.typeName),i=n.getArrayLength(t);return new Ut(t,e,o,i,n,r)}function eu(t,e,n){if(t===null)return NULL;let r=t.$h;if(r!==void 0)return r;let o=t.length,i=We(e.typeName),s=e.newArray.call(n,o);if(s.isNull())throw new Error("Unable to construct array");if(o>0){let c=i.byteSize,a=i.write,l=i.toJni,d=Memory.alloc(o*i.byteSize);for(let p=0;p!==o;p++)a(d.add(p*c),l(t[p]));e.setRegion.call(n,s,0,o,d),n.throwIfExceptionPending()}return s}function tu(t,e){if(t===null)return!0;if(t instanceof Ut)return t.$s.typeName===e;if(!(typeof t=="object"&&t.length!==void 0))return!1;let r=We(e);return Array.prototype.every.call(t,o=>r.isCompatible(o))}function Ut(t,e,n,r,o,i=!0){if(i){let s=o.newGlobalRef(t);this.$h=s,this.$r=Script.bindWeak(this,o.vm.makeHandleDestructor(s))}else this.$h=t,this.$r=null;return this.$s=e,this.$t=n,this.length=r,new Proxy(this,Jo)}Jo={has(t,e){return e in t?!0:t.tryParseIndex(e)!==null},get(t,e,n){let r=t.tryParseIndex(e);return r===null?t[e]:t.readElement(r)},set(t,e,n,r){let o=t.tryParseIndex(e);return o===null?(t[e]=n,!0):(t.writeElement(o,n),!0)},ownKeys(t){let e=[],{length:n}=t;for(let r=0;r!==n;r++){let o=r.toString();e.push(o)}return e.push("length"),e},getOwnPropertyDescriptor(t,e){return t.tryParseIndex(e)!==null?{writable:!0,configurable:!0,enumerable:!0}:Object.getOwnPropertyDescriptor(t,e)}};Object.defineProperties(Ut.prototype,{$dispose:{enumerable:!0,value(){let t=this.$r;t!==null&&(this.$r=null,Script.unbindWeak(t))}},$clone:{value(t){return new Ut(this.$h,this.$s,this.$t,this.length,t)}},tryParseIndex:{value(t){if(typeof t=="symbol")return null;let e=parseInt(t);return isNaN(e)||e<0||e>=this.length?null:e}},readElement:{value(t){return this.withElements(e=>{let n=this.$t;return n.fromJni(n.read(e.add(t*n.byteSize)))})}},writeElement:{value(t,e){let{$h:n,$s:r,$t:o}=this,i=cr.getEnv(),s=Memory.alloc(o.byteSize);o.write(s,o.toJni(e)),r.setRegion.call(i,n,t,1,s)}},withElements:{value(t){let{$h:e,$s:n}=this,r=cr.getEnv(),o=n.getElements.call(r,e);if(o.isNull())throw new Error("Unable to get array elements");try{return t(o)}finally{n.releaseElements.call(r,e,o)}}},toJSON:{value(){let{length:t,$t:e}=this,{byteSize:n,fromJni:r,read:o}=e;return this.withElements(i=>{let s=[];for(let c=0;c!==t;c++){let a=r(o(i.add(c*n)));s.push(a)}return s})}},toString:{value(){return this.toJSON().toString()}}});function Be(t){return"L"+t.replace(/\./g,"/")+";"}function nu(t){return t.charAt(0).toUpperCase()+t.slice(1)}function ve(t){return t}var ru=4,{ensureClassInitialized:Ho,makeMethodMangler:Yo}=Ft,ou=8,fr=1,mt=2,Ne=3,ur=1,hr=2,Bt=1,Xo=2,Zo=Symbol("PENDING_USE"),qo="/data/local/tmp",{getCurrentThreadId:Vt,pointerSize:_t}=Process,ue={state:"empty",factories:[],loaders:null,Integer:null},G=null,K=null,ei=null,ti=null,ni=null,ri=null,oi=null,Wo=null,pr=null,Qe=new Map,je=class t{static _initialize(e,n){G=e,K=n,ei=n.flavor==="art",n.flavor==="jvm"&&(Ho=Co,Yo=Ao)}static _disposeAll(e){ue.factories.forEach(n=>{n._dispose(e)})}static get(e){let n=Iu(),r=n.factories[0];if(e===null)return r;let o=n.loaders.get(e);if(o!==null){let s=r.cast(o,n.Integer);return n.factories[s.intValue()]}let i=new t;return i.loader=e,i.cacheDir=r.cacheDir,gr(i,e),i}constructor(){this.cacheDir=qo,this.codeCacheDir=qo+"/dalvik-cache",this.tempFileNaming={prefix:"frida",suffix:""},this._classes={},this._classHandles=new ft(10,su),this._patchedMethods=new Set,this._loader=null,this._types=[{},{}],ue.factories.push(this)}_dispose(e){Array.from(this._patchedMethods).forEach(n=>{n.implementation=null}),this._patchedMethods.clear(),qn(),this._classHandles.dispose(e),this._classes={}}get loader(){return this._loader}set loader(e){let n=this._loader===null&&e!==null;this._loader=e,n&&ue.state==="ready"&&this===ue.factories[0]&&gr(this,e)}use(e,n={}){let r=n.cache!=="skip",o=r?this._getUsedClass(e):void 0;if(o===void 0)try{let i=G.getEnv(),{_loader:s}=this,c=s!==null?cu(e,s,i):au(e);o=this._make(e,c,i)}finally{r&&this._setUsedClass(e,o)}return o}_getUsedClass(e){let n;for(;(n=this._classes[e])===Zo;)Thread.sleep(.05);return n===void 0&&(this._classes[e]=Zo),n}_setUsedClass(e,n){n!==void 0?this._classes[e]=n:delete this._classes[e]}_make(e,n,r){let o=iu(),i=Object.create(yr.prototype,{[Symbol.for("n")]:{value:e},$n:{get(){return this[Symbol.for("n")]}},[Symbol.for("C")]:{value:o},$C:{get(){return this[Symbol.for("C")]}},[Symbol.for("w")]:{value:null,writable:!0},$w:{get(){return this[Symbol.for("w")]},set(a){this[Symbol.for("w")]=a}},[Symbol.for("_s")]:{writable:!0},$_s:{get(){return this[Symbol.for("_s")]},set(a){this[Symbol.for("_s")]=a}},[Symbol.for("c")]:{value:[null]},$c:{get(){return this[Symbol.for("c")]}},[Symbol.for("m")]:{value:new Map},$m:{get(){return this[Symbol.for("m")]}},[Symbol.for("l")]:{value:null,writable:!0},$l:{get(){return this[Symbol.for("l")]},set(a){this[Symbol.for("l")]=a}},[Symbol.for("gch")]:{value:n},$gch:{get(){return this[Symbol.for("gch")]}},[Symbol.for("f")]:{value:this},$f:{get(){return this[Symbol.for("f")]}}});o.prototype=i;let s=new o(null);i[Symbol.for("w")]=s,i.$w=s;let c=s.$borrowClassHandle(r);try{let a=c.value;Ho(r,a),i.$l=Ue.build(a,r)}finally{c.unref(r)}return s}retain(e){let n=G.getEnv();return e.$clone(n)}cast(e,n,r){let o=G.getEnv(),i=e.$h;i===void 0&&(i=e);let s=n.$borrowClassHandle(o);try{if(!o.isInstanceOf(i,s.value))throw new Error(`Cast from '${o.getObjectClassName(i)}' to '${n.$n}' isn't possible`)}finally{s.unref(o)}let c=n.$C;return new c(i,Bt,o,r)}wrap(e,n,r){let o=n.$C,i=new o(e,Bt,r,!1);return i.$r=Script.bindWeak(i,G.makeHandleDestructor(e)),i}array(e,n){let r=G.getEnv(),o=We(e);o!==null&&(e=o.name);let i=dr("["+e,!1,this),s=i.toJni(n,r);return i.fromJni(s,r,!0)}registerClass(e){let n=G.getEnv(),r=[];try{let o=this.use("java.lang.Class"),i=n.javaLangReflectMethod(),s=n.vaMethod("pointer",[]),c=e.name,a=e.implements||[],l=e.superClass||this.use("java.lang.Object"),d=[],p=[],f={name:Be(c),sourceFileName:Tu(c),superClass:Be(l.$n),interfaces:a.map(S=>Be(S.$n)),fields:d,methods:p},u=a.slice();a.forEach(S=>{Array.prototype.slice.call(S.class.getInterfaces()).forEach(L=>{let j=this.cast(L,o).getCanonicalName();u.push(this.use(j))})});let _=e.fields||{};Object.getOwnPropertyNames(_).forEach(S=>{let L=this._getType(_[S]);d.push([S,L.name])});let h={},g={};u.forEach(S=>{let L=S.$borrowClassHandle(n);r.push(L);let j=L.value;S.$ownMembers.filter(w=>S[w].overloads!==void 0).forEach(w=>{let R=S[w],D=R.overloads,U=D.map(F=>Ko(w,F.returnType,F.argumentTypes));h[w]=[R,U,j],D.forEach((F,z)=>{let X=U[z];g[X]=[F,j]})})});let b=e.methods||{},C=Object.keys(b).reduce((S,L)=>{let j=b[L],w=L==="$init"?"<init>":L;return j instanceof Array?S.push(...j.map(R=>[w,R])):S.push([w,j]),S},[]),A=[];C.forEach(([S,L])=>{let j=Ne,w,R,D=[],U;if(typeof L=="function"){let te=h[S];if(te!==void 0&&Array.isArray(te)){let[ae,Q,ie]=te;if(Q.length>1)throw new Error(`More than one overload matching '${S}': signature must be specified`);delete g[Q[0]];let Y=ae.overloads[0];j=Y.type,w=Y.returnType,R=Y.argumentTypes,U=L;let ne=n.toReflectedMethod(ie,Y.handle,0),Se=s(n.handle,ne,i.getGenericExceptionTypes);D=br(n,Se).map(Be),n.deleteLocalRef(Se),n.deleteLocalRef(ne)}else w=this._getType("void"),R=[],U=L}else{if(L.isStatic&&(j=mt),w=this._getType(L.returnType||"void"),R=(L.argumentTypes||[]).map(Q=>this._getType(Q)),U=L.implementation,typeof U!="function")throw new Error("Expected a function implementation for method: "+S);let te=Ko(S,w,R),ae=g[te];if(ae!==void 0){let[Q,ie]=ae;delete g[te],j=Q.type,w=Q.returnType,R=Q.argumentTypes;let Y=n.toReflectedMethod(ie,Q.handle,0),ne=s(n.handle,Y,i.getGenericExceptionTypes);D=br(n,ne).map(Be),n.deleteLocalRef(ne),n.deleteLocalRef(Y)}}let F=w.name,z=R.map(te=>te.name),X="("+z.join("")+")"+F;p.push([S,F,z,D,j===mt?ou:0]),A.push([S,X,j,w,R,U])});let M=Object.keys(g);if(M.length>0)throw new Error("Missing implementation for: "+M.join(", "));let O=zt.fromBuffer(Vo(f),this);try{O.load()}finally{O.file.delete()}let k=this.use(e.name),N=C.length;if(N>0){let S=3*_t,L=Memory.alloc(N*S),j=[],w=[];A.forEach(([U,F,z,X,te,ae],Q)=>{let ie=Memory.allocUtf8String(U),Y=Memory.allocUtf8String(F),ne=ii(U,k,z,X,te,ae);L.add(Q*S).writePointer(ie),L.add(Q*S+_t).writePointer(Y),L.add(Q*S+2*_t).writePointer(ne),w.push(ie,Y),j.push(ne)});let R=k.$borrowClassHandle(n);r.push(R);let D=R.value;n.registerNatives(D,L,N),n.throwIfExceptionPending(),k.$nativeMethods=j}return k}finally{r.forEach(o=>{o.unref(n)})}}choose(e,n){let r=G.getEnv(),{flavor:o}=K;if(o==="jvm")this._chooseObjectsJvm(e,r,n);else if(o==="art"){let i=K["art::gc::Heap::VisitObjects"]===void 0;if(i&&K["art::gc::Heap::GetInstances"]===void 0)return this._chooseObjectsJvm(e,r,n);ye(G,r,s=>{i?this._chooseObjectsArtPreA12(e,r,s,n):this._chooseObjectsArtLegacy(e,r,s,n)})}else this._chooseObjectsDalvik(e,r,n)}_chooseObjectsJvm(e,n,r){let o=this.use(e),{jvmti:i}=K,s=1,c=3,a=o.$borrowClassHandle(n),l=int64(a.value.toString());try{let d=new NativeCallback((b,E,C,A)=>(C.writeS64(l),s),"int",["int64","int64","pointer","pointer"]);i.iterateOverInstancesOfClass(a.value,c,d,a.value);let p=Memory.alloc(8);p.writeS64(l);let f=Memory.alloc(ru),u=Memory.alloc(_t);i.getObjectsWithTags(1,p,f,u,NULL);let _=f.readS32(),h=u.readPointer(),g=[];for(let b=0;b!==_;b++)g.push(h.add(b*_t).readPointer());i.deallocate(h);try{for(let b of g){let E=this.cast(b,o);if(r.onMatch(E)==="stop")break}r.onComplete()}finally{g.forEach(b=>{n.deleteLocalRef(b)})}}finally{a.unref(n)}}_chooseObjectsArtPreA12(e,n,r,o){let i=this.use(e),s=lt.$new(r,G),c,a=i.$borrowClassHandle(n);try{let f=K["art::JavaVMExt::DecodeGlobal"](K.vm,r,a.value);c=s.newHandle(f)}finally{a.unref(n)}let l=0,d=ct.$new();K["art::gc::Heap::GetInstances"](K.artHeap,s,c,l,d);let p=d.handles.map(f=>n.newGlobalRef(f));d.$delete(),s.$delete();try{for(let f of p){let u=this.cast(f,i);if(o.onMatch(u)==="stop")break}o.onComplete()}finally{p.forEach(f=>{n.deleteGlobalRef(f)})}}_chooseObjectsArtLegacy(e,n,r,o){let i=this.use(e),s=[],c=K["art::JavaVMExt::AddGlobalRef"],a=K.vm,l,d=i.$borrowClassHandle(n);try{l=K["art::JavaVMExt::DecodeGlobal"](a,r,d.value).toInt32()}finally{d.unref(n)}let p=er(l,f=>{s.push(c(a,r,f))});K["art::gc::Heap::VisitObjects"](K.artHeap,p,NULL);try{for(let f of s){let u=this.cast(f,i);if(o.onMatch(u)==="stop")break}}finally{s.forEach(f=>{n.deleteGlobalRef(f)})}o.onComplete()}_chooseObjectsDalvik(e,n,r){let o=this.use(e);if(K.addLocalReference===null){let s=Process.getModuleByName("libdvm.so"),c;switch(Process.arch){case"arm":c="2d e9 f0 41 05 46 15 4e 0c 46 7e 44 11 b3 43 68";break;case"ia32":c="8d 64 24 d4 89 5c 24 1c 89 74 24 20 e8 ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? 85 d2";break}Memory.scan(s.base,s.size,c,{onMatch:(a,l)=>{let d;if(Process.arch==="arm")a=a.or(1),d=new NativeFunction(a,"pointer",["pointer","pointer"]);else{let p=Memory.alloc(Process.pageSize);Memory.patchCode(p,16,f=>{let u=new X86Writer(f,{pc:p});u.putMovRegRegOffsetPtr("eax","esp",4),u.putMovRegRegOffsetPtr("edx","esp",8),u.putJmpAddress(a),u.flush()}),d=new NativeFunction(p,"pointer",["pointer","pointer"]),d._thunk=p}return K.addLocalReference=d,G.perform(p=>{i(this,p)}),"stop"},onError(a){},onComplete(){K.addLocalReference===null&&r.onComplete()}})}else i(this,n);function i(s,c){let{DVM_JNI_ENV_OFFSET_SELF:a}=Ft,l=c.handle.add(a).readPointer(),d,p=o.$borrowClassHandle(c);try{d=K.dvmDecodeIndirectRef(l,p.value)}finally{p.unref(c)}let f=d.toMatchPattern(),u=K.dvmHeapSourceGetBase(),h=K.dvmHeapSourceGetLimit().sub(u).toInt32();Memory.scan(u,h,f,{onMatch:(g,b)=>{K.dvmIsValidObject(g)&&G.perform(E=>{let C=E.handle.add(a).readPointer(),A,M=K.addLocalReference(C,g);try{A=s.cast(M,o)}finally{E.deleteLocalRef(M)}if(r.onMatch(A)==="stop")return"stop"})},onError(g){},onComplete(){r.onComplete()}})}}openClassFile(e){return new zt(e,null,this)}_getType(e,n=!0){return lr(e,n,this)}};function iu(){return function(t,e,n,r){return yr.call(this,t,e,n,r)}}function yr(t,e,n,r=!0){if(t!==null)if(r){let o=n.newGlobalRef(t);this.$h=o,this.$r=Script.bindWeak(this,G.makeHandleDestructor(o))}else this.$h=t,this.$r=null;else this.$h=null,this.$r=null;return this.$t=e,new Proxy(this,ti)}ti={has(t,e){return e in t?!0:t.$has(e)},get(t,e,n){if(typeof e!="string"||e.startsWith("$")||e==="class")return t[e];let r=t.$find(e);return r!==null?r(n):t[e]},set(t,e,n,r){return t[e]=n,!0},ownKeys(t){return t.$list()},getOwnPropertyDescriptor(t,e){return Object.prototype.hasOwnProperty.call(t,e)?Object.getOwnPropertyDescriptor(t,e):{writable:!1,configurable:!0,enumerable:!0}}};Object.defineProperties(yr.prototype,{[Symbol.for("new")]:{enumerable:!1,get(){return this.$getCtor("allocAndInit")}},$new:{enumerable:!0,get(){return this[Symbol.for("new")]}},[Symbol.for("alloc")]:{enumerable:!1,value(){let t=G.getEnv(),e=this.$borrowClassHandle(t);try{let n=t.allocObject(e.value);return this.$f.cast(n,this)}finally{e.unref(t)}}},$alloc:{enumerable:!0,get(){return this[Symbol.for("alloc")]}},[Symbol.for("init")]:{enumerable:!1,get(){return this.$getCtor("initOnly")}},$init:{enumerable:!0,get(){return this[Symbol.for("init")]}},[Symbol.for("dispose")]:{enumerable:!1,value(){let t=this.$r;t!==null&&(this.$r=null,Script.unbindWeak(t)),this.$h!==null&&(this.$h=void 0)}},$dispose:{enumerable:!0,get(){return this[Symbol.for("dispose")]}},[Symbol.for("clone")]:{enumerable:!1,value(t){let e=this.$C;return new e(this.$h,this.$t,t)}},$clone:{value(t){return this[Symbol.for("clone")](t)}},[Symbol.for("class")]:{enumerable:!1,get(){let t=G.getEnv(),e=this.$borrowClassHandle(t);try{let n=this.$f;return n.cast(e.value,n.use("java.lang.Class"))}finally{e.unref(t)}}},class:{enumerable:!0,get(){return this[Symbol.for("class")]}},[Symbol.for("className")]:{enumerable:!1,get(){let t=this.$h;return t===null?this.$n:G.getEnv().getObjectClassName(t)}},$className:{enumerable:!0,get(){return this[Symbol.for("className")]}},[Symbol.for("ownMembers")]:{enumerable:!1,get(){return this.$l.list()}},$ownMembers:{enumerable:!0,get(){return this[Symbol.for("ownMembers")]}},[Symbol.for("super")]:{enumerable:!1,get(){let t=G.getEnv(),e=this.$s.$C;return new e(this.$h,Xo,t)}},$super:{enumerable:!0,get(){return this[Symbol.for("super")]}},[Symbol.for("s")]:{enumerable:!1,get(){let t=Object.getPrototypeOf(this),e=t.$_s;if(e===void 0){let n=G.getEnv(),r=this.$borrowClassHandle(n);try{let o=n.getSuperclass(r.value);if(o.isNull())e=null;else try{let i=n.getClassName(o),s=t.$f;if(e=s._getUsedClass(i),e===void 0)try{let c=lu(this);e=s._make(i,c,n)}finally{s._setUsedClass(i,e)}}finally{n.deleteLocalRef(o)}}finally{r.unref(n)}t.$_s=e}return e}},$s:{get(){return this[Symbol.for("s")]}},[Symbol.for("isSameObject")]:{enumerable:!1,value(t){return G.getEnv().isSameObject(t.$h,this.$h)}},$isSameObject:{value(t){return this[Symbol.for("isSameObject")](t)}},[Symbol.for("getCtor")]:{enumerable:!1,value(t){let e=this.$c,n=e[0];if(n===null){let r=G.getEnv(),o=this.$borrowClassHandle(r);try{n=du(o.value,this.$w,r),e[0]=n}finally{o.unref(r)}}return n[t]}},$getCtor:{value(t){return this[Symbol.for("getCtor")](t)}},[Symbol.for("borrowClassHandle")]:{enumerable:!1,value(t){let e=this.$n,n=this.$f._classHandles,r=n.get(e);return r===void 0&&(r=new Er(this.$gch(t),t),n.set(e,r,t)),r.ref()}},$borrowClassHandle:{value(t){return this[Symbol.for("borrowClassHandle")](t)}},[Symbol.for("copyClassHandle")]:{enumerable:!1,value(t){let e=this.$borrowClassHandle(t);try{return t.newLocalRef(e.value)}finally{e.unref(t)}}},$copyClassHandle:{value(t){return this[Symbol.for("copyClassHandle")](t)}},[Symbol.for("getHandle")]:{enumerable:!1,value(t){let e=this.$h;if(e===void 0)throw new Error("Wrapper is disposed; perhaps it was borrowed from a hook instead of calling Java.retain() to make a long-lived wrapper?");return e}},$getHandle:{value(t){return this[Symbol.for("getHandle")](t)}},[Symbol.for("list")]:{enumerable:!1,value(){let t=this.$s,e=t!==null?t.$list():[],n=this.$l;return Array.from(new Set(e.concat(n.list())))}},$list:{get(){return this[Symbol.for("list")]}},[Symbol.for("has")]:{enumerable:!1,value(t){if(this.$m.has(t)||this.$l.has(t))return!0;let r=this.$s;return!!(r!==null&&r.$has(t))}},$has:{value(t){return this[Symbol.for("has")](t)}},[Symbol.for("find")]:{enumerable:!1,value(t){let e=this.$m,n=e.get(t);if(n!==void 0)return n;let o=this.$l.find(t);if(o!==null){let s=G.getEnv(),c=this.$borrowClassHandle(s);try{n=uu(t,o,c.value,this.$w,s)}finally{c.unref(s)}return e.set(t,n),n}let i=this.$s;return i!==null?i.$find(t):null}},$find:{value(t){return this[Symbol.for("find")](t)}},[Symbol.for("toJSON")]:{enumerable:!1,value(){let t=this.$n;if(this.$h===null)return`<class: ${t}>`;let n=this.$className;return t===n?`<instance: ${t}>`:`<instance: ${t}, $className: ${n}>`}},toJSON:{get(){return this[Symbol.for("toJSON")]}}});function Er(t,e){this.value=e.newGlobalRef(t),e.deleteLocalRef(t),this.refs=1}Er.prototype.ref=function(){return this.refs++,this};Er.prototype.unref=function(t){--this.refs===0&&t.deleteGlobalRef(this.value)};function su(t,e){t.unref(e)}function au(t){let e=t.replace(/\./g,"/");return function(n){let r=Vt();ai(r);try{return n.findClass(e)}finally{ci(r)}}}function cu(t,e,n){return pr===null&&(Wo=n.vaMethod("pointer",["pointer"]),pr=e.loadClass.overload("java.lang.String").handle),n=null,function(r){let o=r.newStringUtf(t),i=Vt();ai(i);try{let s=Wo(r.handle,e.$h,pr,o);return r.throwIfExceptionPending(),s}finally{ci(i),r.deleteLocalRef(o)}}}function lu(t){return function(e){let n=t.$borrowClassHandle(e);try{return e.getSuperclass(n.value)}finally{n.unref(e)}}}function du(t,e,n){let{$n:r,$f:o}=e,i=Cu(r),s=n.javaLangClass(),c=n.javaLangReflectConstructor(),a=n.vaMethod("pointer",[]),l=n.vaMethod("uint8",[]),d=[],p=[],f=o._getType(r,!1),u=o._getType("void",!1),_=a(n.handle,t,s.getDeclaredConstructors);try{let h=n.getArrayLength(_);if(h!==0)for(let g=0;g!==h;g++){let b,E,C=n.getObjectArrayElement(_,g);try{b=n.fromReflectedMethod(C),E=a(n.handle,C,c.getGenericParameterTypes)}finally{n.deleteLocalRef(C)}let A;try{A=br(n,E).map(M=>o._getType(M))}finally{n.deleteLocalRef(E)}d.push(Ke(i,e,fr,b,f,A,n)),p.push(Ke(i,e,Ne,b,u,A,n))}else{if(l(n.handle,t,s.isInterface))throw new Error("cannot instantiate an interface");let b=n.javaLangObject(),E=n.getMethodId(b,"<init>","()V");d.push(Ke(i,e,fr,E,f,[],n)),p.push(Ke(i,e,Ne,E,u,[],n))}}finally{n.deleteLocalRef(_)}if(p.length===0)throw new Error("no supported overloads");return{allocAndInit:_r(d),initOnly:_r(p)}}function uu(t,e,n,r,o){return e.startsWith("m")?pu(t,e,n,r,o):vu(t,e,n,r,o)}function pu(t,e,n,r,o){let{$f:i}=r,s=e.split(":").slice(1),c=o.javaLangReflectMethod(),a=o.vaMethod("pointer",[]),l=o.vaMethod("uint8",[]),d=s.map(f=>{let u=f[0]==="s"?mt:Ne,_=ptr(f.substr(1)),h,g=[],b=o.toReflectedMethod(n,_,u===mt?1:0);try{let E=!!l(o.handle,b,c.isVarArgs),C=a(o.handle,b,c.getGenericReturnType);o.throwIfExceptionPending();try{h=i._getType(o.getTypeName(C))}finally{o.deleteLocalRef(C)}let A=a(o.handle,b,c.getParameterTypes);try{let M=o.getArrayLength(A);for(let O=0;O!==M;O++){let k=o.getObjectArrayElement(A,O),N;try{N=E&&O===M-1?o.getArrayTypeName(k):o.getTypeName(k)}finally{o.deleteLocalRef(k)}let S=i._getType(N);g.push(S)}}finally{o.deleteLocalRef(A)}}catch{return null}finally{o.deleteLocalRef(b)}return Ke(t,r,u,_,h,g,o)}).filter(f=>f!==null);if(d.length===0)throw new Error("No supported overloads");t==="valueOf"&&bu(d);let p=_r(d);return function(f){return p}}function _r(t){let e=fu();return Object.setPrototypeOf(e,ni),e._o=t,e}function fu(){let t=function(){return t.invoke(this,arguments)};return t}ni=Object.create(Function.prototype,{overloads:{enumerable:!0,get(){return this._o}},overload:{value(...t){let e=this._o,n=t.length,r=t.join(":");for(let o=0;o!==e.length;o++){let i=e[o],{argumentTypes:s}=i;if(s.length!==n)continue;if(s.map(a=>a.className).join(":")===r)return i}mr(this.methodName,this.overloads,"specified argument types do not match any of:")}},methodName:{enumerable:!0,get(){return this._o[0].methodName}},holder:{enumerable:!0,get(){return this._o[0].holder}},type:{enumerable:!0,get(){return this._o[0].type}},handle:{enumerable:!0,get(){return ze(this),this._o[0].handle}},implementation:{enumerable:!0,get(){return ze(this),this._o[0].implementation},set(t){ze(this),this._o[0].implementation=t}},returnType:{enumerable:!0,get(){return ze(this),this._o[0].returnType}},argumentTypes:{enumerable:!0,get(){return ze(this),this._o[0].argumentTypes}},canInvokeWith:{enumerable:!0,get(t){return ze(this),this._o[0].canInvokeWith}},clone:{enumerable:!0,value(t){return ze(this),this._o[0].clone(t)}},invoke:{value(t,e){let n=this._o,r=t.$h!==null;for(let o=0;o!==n.length;o++){let i=n[o];if(i.canInvokeWith(e)){if(i.type===Ne&&!r){let s=this.methodName;if(s==="toString")return`<class: ${t.$n}>`;throw new Error(s+": cannot call instance method without an instance")}return i.apply(t,e)}}if(this.methodName==="toString")return`<class: ${t.$n}>`;mr(this.methodName,this.overloads,"argument types do not match any of:")}}});function Ko(t,e,n){return`${e.className} ${t}(${n.map(r=>r.className).join(", ")})`}function ze(t){let e=t._o;e.length>1&&mr(e[0].methodName,e,"has more than one overload, use .overload(<signature>) to choose from:")}function mr(t,e,n){let o=e.slice().sort((i,s)=>i.argumentTypes.length-s.argumentTypes.length).map(i=>i.argumentTypes.length>0?".overload('"+i.argumentTypes.map(c=>c.className).join("', '")+"')":".overload()");throw new Error(`${t}(): ${n}
	${o.join(`
	`)}`)}function Ke(t,e,n,r,o,i,s,c){let a=o.type,l=i.map(f=>f.type);s===null&&(s=G.getEnv());let d,p;return n===Ne?(d=s.vaMethod(a,l,c),p=s.nonvirtualVaMethod(a,l,c)):n===mt?(d=s.staticVaMethod(a,l,c),p=d):(d=s.constructor(l,c),p=d),hu([t,e,n,r,o,i,d,p])}function hu(t){let e=_u();return Object.setPrototypeOf(e,ri),e._p=t,e}function _u(){let t=function(){return t.invoke(this,arguments)};return t}ri=Object.create(Function.prototype,{methodName:{enumerable:!0,get(){return this._p[0]}},holder:{enumerable:!0,get(){return this._p[1]}},type:{enumerable:!0,get(){return this._p[2]}},handle:{enumerable:!0,get(){return this._p[3]}},implementation:{enumerable:!0,get(){let t=this._r;return t!==void 0?t:null},set(t){let e=this._p,n=e[1];if(e[2]===fr)throw new Error("Reimplementing $new is not possible; replace implementation of $init instead");let o=this._r;if(o!==void 0&&(n.$f._patchedMethods.delete(this),o._m.revert(G),this._r=void 0),t!==null){let[i,s,c,a,l,d]=e,p=ii(i,s,c,l,d,t,this),f=Yo(a);p._m=f,this._r=p,f.replace(p,c===Ne,d,G,K),n.$f._patchedMethods.add(this)}}},returnType:{enumerable:!0,get(){return this._p[4]}},argumentTypes:{enumerable:!0,get(){return this._p[5]}},canInvokeWith:{enumerable:!0,value(t){let e=this._p[5];return t.length!==e.length?!1:e.every((n,r)=>n.isCompatible(t[r]))}},clone:{enumerable:!0,value(t){let e=this._p.slice(0,6);return Ke(...e,null,t)}},invoke:{value(t,e){let n=G.getEnv(),r=this._p,o=r[2],i=r[4],s=r[5],c=this._r,a=o===Ne,l=e.length,d=2+l;n.pushLocalFrame(d);let p=null;try{let f;a?f=t.$getHandle():(p=t.$borrowClassHandle(n),f=p.value);let u,_=t.$t;c===void 0?u=r[3]:(u=c._m.resolveTarget(t,a,n,K),ei&&c._c.has(Vt())&&(_=Xo));let h=[n.handle,f,u];for(let E=0;E!==l;E++)h.push(s[E].toJni(e[E],n));let g;_===Bt?g=r[6]:(g=r[7],a&&h.splice(2,0,t.$copyClassHandle(n)));let b=g.apply(null,h);return n.throwIfExceptionPending(),i.fromJni(b,n,!0)}finally{p!==null&&p.unref(n),n.popLocalFrame(NULL)}}},toString:{enumerable:!0,value(){return`function ${this.methodName}(${this.argumentTypes.map(t=>t.className).join(", ")}): ${this.returnType.className}`}}});function ii(t,e,n,r,o,i,s=null){let c=new Set,a=mu([t,e,n,r,o,i,s,c]),l=new NativeCallback(a,r.type,["pointer","pointer"].concat(o.map(d=>d.type)));return l._c=c,l}function mu(t){return function(){return gu(arguments,t)}}function gu(t,e){let n=new y(t[0],G),[r,o,i,s,c,a,l,d]=e,p=[],f;if(i===Ne){let h=o.$C;f=new h(t[1],Bt,n,!1)}else f=o;let u=Vt();n.pushLocalFrame(3);let _=!0;G.link(u,n);try{d.add(u);let h;l===null||!Qe.has(u)?h=a:h=l;let g=[],b=t.length-2;for(let A=0;A!==b;A++){let O=c[A].fromJni(t[2+A],n,!1);g.push(O),p.push(O)}let E=h.apply(f,g);if(!s.isCompatible(E))throw new Error(`Implementation for ${r} expected return value compatible with ${s.className}`);let C=s.toJni(E,n);return s.type==="pointer"&&(C=n.popLocalFrame(C),_=!1,p.push(E)),C}catch(h){let g=h.$h;return g!==void 0?n.throw(g):Script.nextTick(()=>{throw h}),s.defaultValue}finally{G.unlink(u),_&&n.popLocalFrame(NULL),d.delete(u),p.forEach(h=>{if(h===null)return;let g=h.$dispose;g!==void 0&&g.call(h)})}}function bu(t){let{holder:e,type:n}=t[0];t.some(o=>o.type===n&&o.argumentTypes.length===0)||t.push(yu([e,n]))}function yu(t){let e=Eu();return Object.setPrototypeOf(e,oi),e._p=t,e}function Eu(){return function(){return this}}oi=Object.create(Function.prototype,{methodName:{enumerable:!0,get(){return"valueOf"}},holder:{enumerable:!0,get(){return this._p[0]}},type:{enumerable:!0,get(){return this._p[1]}},handle:{enumerable:!0,get(){return NULL}},implementation:{enumerable:!0,get(){return null},set(t){}},returnType:{enumerable:!0,get(){let t=this.holder;return t.$f.use(t.$n)}},argumentTypes:{enumerable:!0,get(){return[]}},canInvokeWith:{enumerable:!0,value(t){return t.length===0}},clone:{enumerable:!0,value(t){throw new Error("Invalid operation")}}});function vu(t,e,n,r,o){let i=e[2]==="s"?ur:hr,s=ptr(e.substr(3)),{$f:c}=r,a,l=o.toReflectedField(n,s,i===ur?1:0);try{a=o.vaMethod("pointer",[])(o.handle,l,o.javaLangReflectField().getGenericType),o.throwIfExceptionPending()}finally{o.deleteLocalRef(l)}let d;try{d=c._getType(o.getTypeName(a))}finally{o.deleteLocalRef(a)}let p,f,u=d.type;return i===ur?(p=o.getStaticField(u),f=o.setStaticField(u)):(p=o.getField(u),f=o.setField(u)),Su([i,d,s,p,f])}function Su(t){return function(e){return new si([e].concat(t))}}function si(t){this._p=t}Object.defineProperties(si.prototype,{value:{enumerable:!0,get(){let[t,e,n,r,o]=this._p,i=G.getEnv();i.pushLocalFrame(4);let s=null;try{let c;if(e===hr){if(c=t.$getHandle(),c===null)throw new Error("Cannot access an instance field without an instance")}else s=t.$borrowClassHandle(i),c=s.value;let a=o(i.handle,c,r);return i.throwIfExceptionPending(),n.fromJni(a,i,!0)}finally{s!==null&&s.unref(i),i.popLocalFrame(NULL)}},set(t){let[e,n,r,o,,i]=this._p,s=G.getEnv();s.pushLocalFrame(4);let c=null;try{let a;if(n===hr){if(a=e.$getHandle(),a===null)throw new Error("Cannot access an instance field without an instance")}else c=e.$borrowClassHandle(s),a=c.value;if(!r.isCompatible(t))throw new Error(`Expected value compatible with ${r.className}`);let l=r.toJni(t,s);i(s.handle,a,o,l),s.throwIfExceptionPending()}finally{c!==null&&c.unref(s),s.popLocalFrame(NULL)}}},holder:{enumerable:!0,get(){return this._p[0]}},fieldType:{enumerable:!0,get(){return this._p[1]}},fieldReturnType:{enumerable:!0,get(){return this._p[2]}},toString:{enumerable:!0,value(){let t=`Java.Field{holder: ${this.holder}, fieldType: ${this.fieldType}, fieldReturnType: ${this.fieldReturnType}, value: ${this.value}}`;return t.length<200?t:`Java.Field{
	holder: ${this.holder},
	fieldType: ${this.fieldType},
	fieldReturnType: ${this.fieldReturnType},
	value: ${this.value},
}`.split(`
`).map(n=>n.length>200?n.slice(0,n.indexOf(" ")+1)+"...,":n).join(`
`)}}});var zt=class t{static fromBuffer(e,n){let r=Qo(n),o=r.getCanonicalPath().toString(),i=new File(o,"w");return i.write(e.buffer),i.close(),wu(o,n),new t(o,r,n)}constructor(e,n,r){this.path=e,this.file=n,this._factory=r}load(){let{_factory:e}=this,{codeCacheDir:n}=e,r=e.use("dalvik.system.DexClassLoader"),o=e.use("java.io.File"),i=this.file;if(i===null&&(i=e.use("java.io.File").$new(this.path)),!i.exists())throw new Error("File not found");o.$new(n).mkdirs(),e.loader=r.$new(i.getCanonicalPath(),n,null,e.loader),G.preventDetachDueToClassLoader()}getClassNames(){let{_factory:e}=this,n=e.use("dalvik.system.DexFile"),r=Qo(e),o=n.loadDex(this.path,r.getCanonicalPath(),0),i=[],s=o.entries();for(;s.hasMoreElements();)i.push(s.nextElement().toString());return i}};function Qo(t){let{cacheDir:e,tempFileNaming:n}=t,r=t.use("java.io.File"),o=r.$new(e);return o.mkdirs(),r.createTempFile(n.prefix,n.suffix+".dex",o)}function wu(t,e){e.use("java.io.File").$new(t).setWritable(!1,!1)}function Iu(){switch(ue.state){case"empty":{ue.state="pending";let t=ue.factories[0],e=t.use("java.util.HashMap"),n=t.use("java.lang.Integer");ue.loaders=e.$new(),ue.Integer=n;let r=t.loader;return r!==null&&gr(t,r),ue.state="ready",ue}case"pending":do Thread.sleep(.05);while(ue.state==="pending");return ue;case"ready":return ue}}function gr(t,e){let{factories:n,loaders:r,Integer:o}=ue,i=o.$new(n.indexOf(t));r.put(e,i);for(let s=e.getParent();s!==null&&!r.containsKey(s);s=s.getParent())r.put(s,i)}function ai(t){let e=Qe.get(t);e===void 0&&(e=0),e++,Qe.set(t,e)}function ci(t){let e=Qe.get(t);if(e===void 0)throw new Error(`Thread ${t} is not ignored`);e--,e===0?Qe.delete(t):Qe.set(t,e)}function Cu(t){return t.slice(t.lastIndexOf(".")+1)}function br(t,e){let n=[],r=t.getArrayLength(e);for(let o=0;o!==r;o++){let i=t.getObjectArrayElement(e,o);try{n.push(t.getTypeName(i))}finally{t.deleteLocalRef(i)}}return n}function Tu(t){let e=t.split(".");return e[e.length-1]+".java"}var Au=4,li=Process.pointerSize,vr=class{ACC_PUBLIC=1;ACC_PRIVATE=2;ACC_PROTECTED=4;ACC_STATIC=8;ACC_FINAL=16;ACC_SYNCHRONIZED=32;ACC_BRIDGE=64;ACC_VARARGS=128;ACC_NATIVE=256;ACC_ABSTRACT=1024;ACC_STRICT=2048;ACC_SYNTHETIC=4096;constructor(){this.classFactory=null,this.ClassFactory=je,this.vm=null,this.api=null,this._initialized=!1,this._apiError=null,this._wakeupHandler=null,this._pollListener=null,this._pendingMainOps=[],this._pendingVmOps=[],this._cachedIsAppProcess=null;try{this._tryInitialize()}catch{}}_tryInitialize(){if(this._initialized)return!0;if(this._apiError!==null)throw this._apiError;let e;try{e=pt(),this.api=e}catch(r){throw this._apiError=r,r}if(e===null)return!1;let n=new Ie(e);return this.vm=n,Go(n),je._initialize(n,e),this.classFactory=new je,this._initialized=!0,!0}_dispose(){if(this.api===null)return;let{vm:e}=this;e.perform(n=>{je._disposeAll(n),y.dispose(n)}),Script.nextTick(()=>{Ie.dispose(e)})}get available(){return this._tryInitialize()}get androidVersion(){return dt()}synchronized(e,n){let{$h:r=e}=e;if(!(r instanceof NativePointer))throw new Error("Java.synchronized: the first argument `obj` must be either a pointer or a Java instance");let o=this.vm.getEnv();de("VM::MonitorEnter",o.monitorEnter(r));try{n()}finally{o.monitorExit(r)}}enumerateLoadedClasses(e){this._checkAvailable();let{flavor:n}=this.api;n==="jvm"?this._enumerateLoadedClassesJvm(e):n==="art"?this._enumerateLoadedClassesArt(e):this._enumerateLoadedClassesDalvik(e)}enumerateLoadedClassesSync(){let e=[];return this.enumerateLoadedClasses({onMatch(n){e.push(n)},onComplete(){}}),e}enumerateClassLoaders(e){this._checkAvailable();let{flavor:n}=this.api;if(n==="jvm")this._enumerateClassLoadersJvm(e);else if(n==="art")this._enumerateClassLoadersArt(e);else throw new Error("Enumerating class loaders is not supported on Dalvik")}enumerateClassLoadersSync(){let e=[];return this.enumerateClassLoaders({onMatch(n){e.push(n)},onComplete(){}}),e}_enumerateLoadedClassesJvm(e){let{api:n,vm:r}=this,{jvmti:o}=n,i=r.getEnv(),s=Memory.alloc(Au),c=Memory.alloc(li);o.getLoadedClasses(s,c);let a=s.readS32(),l=c.readPointer(),d=[];for(let p=0;p!==a;p++)d.push(l.add(p*li).readPointer());o.deallocate(l);try{for(let p of d){let f=i.getClassName(p);e.onMatch(f,p)}e.onComplete()}finally{d.forEach(p=>{i.deleteLocalRef(p)})}}_enumerateClassLoadersJvm(e){this.choose("java.lang.ClassLoader",e)}_enumerateLoadedClassesArt(e){let{vm:n,api:r}=this,o=n.getEnv(),i=r["art::JavaVMExt::AddGlobalRef"],{vm:s}=r;ye(n,o,c=>{let a=$n(l=>{let d=i(s,c,l);try{let p=o.getClassName(d);e.onMatch(p,d)}finally{o.deleteGlobalRef(d)}return!0});r["art::ClassLinker::VisitClasses"](r.artClassLinker.address,a)}),e.onComplete()}_enumerateClassLoadersArt(e){let{classFactory:n,vm:r,api:o}=this,i=r.getEnv(),s=o["art::ClassLinker::VisitClassLoaders"];if(s===void 0)throw new Error("This API is only available on Android >= 7.0");let c=n.use("java.lang.ClassLoader"),a=[],l=o["art::JavaVMExt::AddGlobalRef"],{vm:d}=o;ye(r,i,p=>{let f=Hn(u=>(a.push(l(d,p,u)),!0));Gn(()=>{s(o.artClassLinker.address,f)})});try{a.forEach(p=>{let f=n.cast(p,c);e.onMatch(f)})}finally{a.forEach(p=>{i.deleteGlobalRef(p)})}e.onComplete()}_enumerateLoadedClassesDalvik(e){let{api:n}=this,r=ptr("0xcbcacccd"),o=172,i=8,c=n.gDvm.add(o).readPointer(),a=c.readS32(),d=c.add(12).readPointer(),p=a*i;for(let f=0;f<p;f+=i){let _=d.add(f).add(4).readPointer();if(_.isNull()||_.equals(r))continue;let g=_.add(24).readPointer().readUtf8String();if(g.startsWith("L")){let b=g.substring(1,g.length-1).replace(/\//g,".");e.onMatch(b)}}e.onComplete()}enumerateMethods(e){let{classFactory:n}=this,r=this.vm.getEnv(),o=n.use("java.lang.ClassLoader");return Ue.enumerateMethods(e,this.api,r).map(i=>{let s=i.loader;return i.loader=s!==null?n.wrap(s,o,r):null,i})}scheduleOnMainThread(e){this.performNow(()=>{this._pendingMainOps.push(e);let{_wakeupHandler:n}=this;if(n===null){let{classFactory:r}=this,o=r.use("android.os.Handler"),i=r.use("android.os.Looper");n=o.$new(i.getMainLooper()),this._wakeupHandler=n}this._pollListener===null&&(this._pollListener=Interceptor.attach(Process.getModuleByName("libc.so").getExportByName("epoll_wait"),this._makePollHook()),Interceptor.flush()),n.sendEmptyMessage(1)})}_makePollHook(){let e=Process.id,{_pendingMainOps:n}=this;return function(){if(this.threadId!==e)return;let r;for(;(r=n.shift())!==void 0;)try{r()}catch(o){Script.nextTick(()=>{throw o})}}}perform(e){if(this._checkAvailable(),!this._isAppProcess()||this.classFactory.loader!==null)try{this.vm.perform(e)}catch(n){Script.nextTick(()=>{throw n})}else this._pendingVmOps.push(e),this._pendingVmOps.length===1&&this._performPendingVmOpsWhenReady()}performNow(e){return this._checkAvailable(),this.vm.perform(()=>{let{classFactory:n}=this;if(this._isAppProcess()&&n.loader===null){let o=n.use("android.app.ActivityThread").currentApplication();o!==null&&di(n,o)}return e()})}_performPendingVmOpsWhenReady(){this.vm.perform(()=>{let{classFactory:e}=this,n=e.use("android.app.ActivityThread"),r=n.currentApplication();if(r!==null){di(e,r),this._performPendingVmOps();return}let o=this,i=!1,s="early",c=n.handleBindApplication;c.implementation=function(d){if(d.instrumentationName.value!==null){s="late";let f=e.use("android.app.LoadedApk").makeApplication;f.implementation=function(u,_){return i||(i=!0,ui(e,this),o._performPendingVmOps()),f.apply(this,arguments)}}c.apply(this,arguments)};let l=n.getPackageInfo.overloads.map(d=>[d.argumentTypes.length,d]).sort(([d],[p])=>p-d).map(([d,p])=>p)[0];l.implementation=function(...d){let p=l.call(this,...d);return!i&&s==="early"&&(i=!0,ui(e,p),o._performPendingVmOps()),p}})}_performPendingVmOps(){let{vm:e,_pendingVmOps:n}=this,r;for(;(r=n.shift())!==void 0;)try{e.perform(r)}catch(o){Script.nextTick(()=>{throw o})}}use(e,n){return this.classFactory.use(e,n)}openClassFile(e){return this.classFactory.openClassFile(e)}choose(e,n){this.classFactory.choose(e,n)}retain(e){return this.classFactory.retain(e)}cast(e,n){return this.classFactory.cast(e,n)}array(e,n){return this.classFactory.array(e,n)}backtrace(e){return Zn(this.vm,e)}isMainThread(){let e=this.classFactory.use("android.os.Looper"),n=e.getMainLooper(),r=e.myLooper();return r===null?!1:n.$isSameObject(r)}registerClass(e){return this.classFactory.registerClass(e)}deoptimizeEverything(){let{vm:e}=this;return Qn(e,e.getEnv())}deoptimizeBootImage(){let{vm:e}=this;return Yn(e,e.getEnv())}deoptimizeMethod(e){let{vm:n}=this;return Kn(n,n.getEnv(),e)}_checkAvailable(){if(!this.available)throw new Error("Java API not available")}_isAppProcess(){let e=this._cachedIsAppProcess;if(e===null){if(this.api.flavor==="jvm")return e=!1,this._cachedIsAppProcess=e,e;let n=new NativeFunction(Module.getGlobalExportByName("readlink"),"pointer",["pointer","pointer","pointer"],{exceptions:"propagate"}),r=Memory.allocUtf8String("/proc/self/exe"),o=1024,i=Memory.alloc(o),s=n(r,i,ptr(o)).toInt32();if(s!==-1){let c=i.readUtf8String(s);e=/^\/system\/bin\/app_process/.test(c)}else e=!0;this._cachedIsAppProcess=e}return e}};function di(t,e){let n=t.use("android.os.Process");t.loader=e.getClassLoader(),n.myUid()===n.SYSTEM_UID.value?(t.cacheDir="/data/system",t.codeCacheDir="/data/dalvik-cache"):"getCodeCacheDir"in e?(t.cacheDir=e.getCacheDir().getCanonicalPath(),t.codeCacheDir=e.getCodeCacheDir().getCanonicalPath()):(t.cacheDir=e.getFilesDir().getCanonicalPath(),t.codeCacheDir=e.getCacheDir().getCanonicalPath())}function ui(t,e){let n=t.use("java.io.File");t.loader=e.getClassLoader();let r=n.$new(e.getDataDir()).getCanonicalPath();t.cacheDir=r,t.codeCacheDir=r+"/cache"}var Sr=new vr;Script.bindWeak(Sr,()=>{Sr._dispose()});var pi=Sr;globalThis.Java=pi;})();


/* ===== canvas2d_adapter.js ===== */
/**
 * canvas2d_adapter.js —— 让 dashboard.js 原样跑在 Android Canvas 上
 *
 * 核心思路：
 *   dashboard.js 用的是 HTML Canvas 2D（ctx.arc / ctx.fillStyle / ctx.fillText）
 *   Android 有 Canvas（drawArc / Paint.setColor / drawText）
 *   两者是同一套模型，只需要一个翻译层。
 *
 * 用法：
 *   var c2d = new Canvas2D(androidBitmap, 1920, 532);
 *   var hud = new HUD(c2d);          // 把适配器当 canvas 传进去
 *   hud.autoSpeed = false;
 *   hud.start();
 *   // 每帧调 c2d.flush() 把内容提交到 Bitmap
 *
 * 支持的能力（dashboard.js 实际用到的）：
 *   路径:   beginPath / moveTo / lineTo / arc / arcTo / ellipse
 *           quadraticCurveTo / bezierCurveTo / closePath / rect
 *   绘制:   stroke / fill / fillRect / clearRect / fillText / clip
 *   状态:   save / restore / translate / rotate / scale / setTransform
 *   样式:   fillStyle / strokeStyle / lineWidth / lineCap / lineJoin
 *           font / textAlign / textBaseline / globalAlpha
 *           shadowColor / shadowBlur / letterSpacing / lineDashOffset
 *   渐变:   createLinearGradient / createRadialGradient（含 addColorStop）
 *   测量:   measureText
 *
 * 不支持（按需补）：
 *   · drawImage（dashboard.js 没用）
 *   · Path2D 对象
 *   · 图像滤镜
 *
 * 性能关键：
 *   · Paint 对象复用（不每帧 new）
 *   · Path 复用（beginPath 时 reset）
 *   · 渐变对象缓存（同参数复用）
 */

/* ── 运行环境判定 ─────────────────────────────────────────────── */

/* 真 Android（Frida）上 Java.androidVersion 存在；
 * PC 预览的 C shim 只是同名占位，没有这个字段。
 * 用它决定渐变要不要构建真实的 Android Shader 对象。
 */
var C2D_IS_ANDROID = false;
try {
    C2D_IS_ANDROID = (typeof Java !== "undefined") &&
                     (typeof Java.androidVersion === "string") &&
                     (Java.androidVersion.length > 0);
} catch (eIsAndroid) { C2D_IS_ANDROID = false; }

/* ── Java 类缓存 ───────────────────────────────────────────────
 *
 * Java.use() 在 Android 上是贵操作（查类 + 建 wrapper，微秒~毫秒级）。
 * dashboard.js 每帧画 52 次文字、建 28 个渐变、79 次描边，
 * 如果在热路径里每次都 Java.use，光这一步就能吃掉几百毫秒
 * （2026-11-10 实测：单帧 560ms，其中 draw 占 558ms）。
 *
 * 统一在这里缓存，只在首次真正需要时查一次。
 */
var C2D_CLS = {
    linearGrad: null, radialGrad: null, tileMode: null,
    align: null
};

/* ── 渐变对象 ─────────────────────────────────────────────────── */

/**
 * 渐变。一份数据，两种消费者：
 *
 *   真 Android  → _android 指向 android.graphics.LinearGradient / RadialGradient
 *    PC 预览     → 对象本身就是描述符，C shim 直接读 _gradKind/_o0/_c0 ...
 *
 * 关键：描述符字段（_gradKind / _x0 / _n / _o{k} / _c{k}）
 * 必须始终存在于对象上，这样 p.setShader(this) 两种环境都能吃。
 */
function C2DGradient(kind, x0, y0, x1, y1, r1) {
    this._gradKind = kind;          /* 1 = linear, 2 = radial */
    this._x0 = x0; this._y0 = y0;
    this._x1 = x1; this._y1 = y1;
    this._r1 = r1 || 0;
    this._n = 0;
    this._stops = [];
    this._android = null;           /* 懒构建的真 Android Shader */
    this._dirty = true;
}

C2DGradient.prototype.addColorStop = function (offset, color) {
    this._stops.push({ o: offset, c: c2dParseColor(color) });
    this._n = this._stops.length;
    this._dirty = true;
    this._syncDescriptor();
};

/* 把 stops 摊平到 _o0/_c0/_o1/_c1 ...，供 C shim 读取 */
C2DGradient.prototype._syncDescriptor = function () {
    var s = this._stops.slice().sort(function (a, b) { return a.o - b.o; });
    this._n = s.length;
    for (var i = 0; i < s.length; i++) {
        this["_o" + i] = s[i].o;
        this["_c" + i] = s[i].c;
    }
};

/* 取真 Android Shader（PC 上返回 null，不影响描述符） */
C2DGradient.prototype._buildAndroid = function () {
    if (!C2D_IS_ANDROID) return null;
    if (this._android !== null && !this._dirty) return this._android;
    if (this._stops.length === 0) return null;
    try {
        /* ★ 复用 addColorStop 时已排好序的数组（2026-11-10 性能）
         *
         * 原来这里又 slice().sort() 一次 —— 每帧 28 个渐变，
         * 每个都新建数组 + 排序 + 建两个 Java 数组，纯浪费。
         * _syncDescriptor 已经把颜色摊平到 _o0/_c0/_o1/_c1…，
         * 这里直接读，不再排序、不再 slice。
         */
        var n = this._n;
        var colors = new Array(n), pos = new Array(n);
        for (var i = 0; i < n; i++) {
            colors[i] = this["_c" + i];
            pos[i] = this["_o" + i];
        }
        var ca = Java.array("int", colors);
        var pa = Java.array("float", pos);
        /* ★ Java.use 缓存在模块级（2026-11-10 性能）
         *
         * Java.use 每次调用都要查类 + 建 wrapper，在 Android 上是
         * 微秒~毫秒级开销。dashboard.js 每帧新建 28 个渐变，
         * 每次都 Java.use 两个类 → 每帧 56 次多余调用。
         * 缓存到模块级变量，只查一次。
         */
        if (!C2D_CLS.linearGrad) {
            C2D_CLS.linearGrad = Java.use("android.graphics.LinearGradient");
            C2D_CLS.radialGrad = Java.use("android.graphics.RadialGradient");
            C2D_CLS.tileMode   = Java.use("android.graphics.Shader$TileMode");
        }
        var CLAMP = C2D_CLS.tileMode.CLAMP.value;
        if (this._gradKind === 1) {
            var LG = C2D_CLS.linearGrad;
            this._android = LG.$new(this._x0, this._y0, this._x1, this._y1,
                                    ca, pa, CLAMP);
        } else {
            var RG = C2D_CLS.radialGrad;
            this._android = RG.$new(this._x1, this._y1, this._r1,
                                    ca, pa, CLAMP);
        }
        this._dirty = false;
        return this._android;
    } catch (e) {
        return null;
    }
};

/* 给 _applyPaint 用：真 Android 返回 Shader，PC 返回自身（描述符） */
C2DGradient.prototype.shaderFor = function () {
    var a = this._buildAndroid();
    return a ? a : this;
};

/* ── 颜色解析 ─────────────────────────────────────────────────── */

/**
 * 解析 #RRGGBB / #AARRGGBB / rgba(r,g,b,a) → Android int
 *
 * Android 的 Paint.setColor 要 0xAARRGGBB 的有符号 int32。
 */
function c2dParseColor(s) {
    if (typeof s === 'number') return s | 0;
    if (!s) return 0xFF000000;
    s = String(s).trim();

    /* rgba(r, g, b, a) 或 rgb(r, g, b) */
    if (s.indexOf('rgb') === 0) {
        var m = /rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)/.exec(s);
        if (m) {
            var r = Math.round(parseFloat(m[1]));
            var g = Math.round(parseFloat(m[2]));
            var b = Math.round(parseFloat(m[3]));
            var a = (m[4] !== undefined) ? Math.round(parseFloat(m[4]) * 255) : 255;
            return ((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF)) | 0;
        }
        return 0xFF000000;
    }

    /* #RGB / #RRGGBB / #AARRGGBB */
    if (s.charAt(0) === '#') s = s.substring(1);
    var v = 0, len = s.length;
    for (var i = 0; i < len; i++) {
        var ch = s.charAt(i), d = -1;
        if (ch >= '0' && ch <= '9') d = ch.charCodeAt(0) - 48;
        else if (ch >= 'a' && ch <= 'f') d = ch.charCodeAt(0) - 87;
        else if (ch >= 'A' && ch <= 'F') d = ch.charCodeAt(0) - 55;
        else return 0xFF000000;
        v = v * 16 + d;
    }
    if (len === 3) {
        /* #RGB → #RRGGBB */
        var r3 = (v >> 8) & 0xF, g3 = (v >> 4) & 0xF, b3 = v & 0xF;
        v = (r3 * 17) << 16 | (g3 * 17) << 8 | (b3 * 17);
        len = 6;
    }
    if (len <= 6) return (0xFF000000 | v) | 0;
    return v | 0;
}

/* ── 字体解析 ─────────────────────────────────────────────────── */

/**
 * 解析 CSS font 字符串："600 62px Bahnschrift, sans-serif"
 * → { px, bold }
 */
function c2dParseFont(s) {
    var px = 16, bold = false;
    if (!s) return { px: px, bold: bold };
    var m = /(\d+(?:\.\d+)?)px/.exec(s);
    if (m) px = parseFloat(m[1]);
    var w = /(\d{3})/.exec(s);
    if (w) {
        var n = parseInt(w[1], 10);
        if (n >= 600) bold = true;
    }
    if (/\bbold\b/i.test(s)) bold = true;
    return { px: px, bold: bold };
}

/* ══════════════════════════════════════════════════════════════
 * Canvas2D
 * ══════════════════════════════════════════════════════════════ */
function Canvas2D(bitmap, width, height) {
    this.bitmap = bitmap;
    this.W = width || 1920;
    this.H = height || 532;

    /* 样式状态（默认值同 Canvas 2D） */
    this.fillStyle = '#000000';
    this.strokeStyle = '#000000';
    this.lineWidth = 1;
    this.lineCap = 'butt';
    this.lineJoin = 'miter';
    this.globalAlpha = 1;
    this.font = '10px sans-serif';
    this.textAlign = 'start';
    this.textBaseline = 'alphabetic';
    this.shadowColor = 'rgba(0,0,0,0)';
    this.shadowBlur = 0;
    this.letterSpacing = '0px';
    this.lineDashOffset = 0;
    this._dash = [];

    /* Android 对象（懒建，复用） */
    this._canvas = null;
    this._paint = null;
    this._path = null;
    this._rectF = null;
    this._xform = null;
    this._ready = false;
    this._err = '';

    /* 状态栈 */
    this._stack = [];
    this._stackMax = 32;

    /* 渐变缓存：描述字符串 → C2DGradient */
    this._gradCache = {};

    /* 统计 */
    this.stats = { frames: 0, draws: 0, lastMs: 0 };

    /* 内联初始化（不用 this._initAndroid()）
     *
     * 原因：QuickJS 里如果在构造函数体内调 this._method()，
     * 而该方法定义在 prototype 上（文件后部），
     * 某些情况下解析不到 —— 实测报 "TypeError: not a function"。
     * 内联最稳。
     */
    try {
        var Canvas = Java.use('android.graphics.Canvas');
        var Paint = Java.use('android.graphics.Paint');
        var Path = Java.use('android.graphics.Path');
        var RectF = Java.use('android.graphics.RectF');
        this._Canvas = Canvas;
        this._Paint = Paint;
        this._Path = Path;
        this._RectF = RectF;
        this._Align = Java.use('android.graphics.Paint$Align');
        this._Style = Java.use('android.graphics.Paint$Style');
        this._Cap = Java.use('android.graphics.Paint$Cap');
        this._Join = Java.use('android.graphics.Paint$Join');
        this._Typeface = Java.use('android.graphics.Typeface');
        this._Matrix = Java.use('android.graphics.Matrix');

        this._canvas = Canvas.$new(this.bitmap);
        this._paint = Paint.$new();
        this._paint.setAntiAlias(true);
        this._path = Path.$new();
        this._rectF = RectF.$new(0, 0, 0, 0);
        this._xform = this._Matrix.$new();

        /* 字体：有 createFromFile 就用，否则回退 DEFAULT */
        this._typeface = null;
        try {
            var tf = this._Typeface.createFromFile('/system/fonts/NotoSansCJK-Regular.ttc');
            if (tf !== null && tf !== undefined) this._typeface = tf;
        } catch (eTf) {}
        if (this._typeface === null) {
            try { this._typeface = this._Typeface.DEFAULT.value; }
            catch (eTf2) { this._typeface = this._Typeface.DEFAULT; }
        }
        try { this._typefaceBold = this._Typeface.DEFAULT_BOLD.value; }
        catch (eTf3) { this._typefaceBold = this._typeface; }

        this._ready = true;
    } catch (e) {
        this._err = String(e).substring(0, 120);
    }
}

Canvas2D.prototype._initAndroid = function () {
    try {
        var Canvas = Java.use('android.graphics.Canvas');
        var Paint = Java.use('android.graphics.Paint');
        var Path = Java.use('android.graphics.Path');
        var RectF = Java.use('android.graphics.RectF');

        this._Canvas = Canvas;
        this._Paint = Paint;
        this._Path = Path;
        this._RectF = RectF;
        this._Align = Java.use('android.graphics.Paint$Align');
        this._Style = Java.use('android.graphics.Paint$Style');
        this._Cap = Java.use('android.graphics.Paint$Cap');
        this._Join = Java.use('android.graphics.Paint$Join');
        this._Typeface = Java.use('android.graphics.Typeface');
        this._Matrix = Java.use('android.graphics.Matrix');
        this._LinearGradient = Java.use('android.graphics.LinearGradient');
        this._RadialGradient = Java.use('android.graphics.RadialGradient');
        this._Shader = Java.use('android.graphics.Shader');

        this._canvas = Canvas.$new(this.bitmap);
        this._paint = Paint.$new();
        this._paint.setAntiAlias(true);
        this._path = Path.$new();
        this._rectF = RectF.$new(0, 0, 0, 0);
        this._xform = this._Matrix.$new();

        /* 中文字体 */
        this._typeface = null;
        var paths = [
            '/system/fonts/NotoSansCJK-Regular.ttc',
            '/system/fonts/SourceHanSans-VF.ttc',
            '/system/fonts/NotoSerifCJK-Regular.ttc'
        ];
        for (var i = 0; i < paths.length; i++) {
            try {
                var tf = this._Typeface.createFromFile(paths[i]);
                if (tf !== null) { this._typeface = tf; break; }
            } catch (e) {}
        }
        if (this._typeface === null) {
            this._typeface = this._Typeface.DEFAULT.value;
        }
        this._typefaceBold = this._Typeface.DEFAULT_BOLD.value;

        this._ready = true;
    } catch (e) {
        this._err = String(e).substring(0, 120);
    }
};

/* ── 给 dashboard.js 的 canvas 接口 ──────────────────────────── */

Canvas2D.prototype.getContext = function (kind) {
    if (kind !== '2d') return null;
    return this;
};

/* width / height 属性（dashboard.js 的 resize 会写） */
Object.defineProperty(Canvas2D.prototype, 'width', {
    get: function () { return this.W; },
    set: function (v) { /* 忽略，尺寸固定 */ }
});
Object.defineProperty(Canvas2D.prototype, 'height', {
    get: function () { return this.H; },
    set: function (v) { }
});
/* style 对象（dashboard.js 会写 style.width 等） */
Object.defineProperty(Canvas2D.prototype, 'style', {
    get: function () {
        if (!this._style) this._style = {};
        return this._style;
    }
});
Object.defineProperty(Canvas2D.prototype, 'parentElement', {
    get: function () { return null; }     /* 没有 DOM，resize 会跳过 */
});

/* ── 状态栈 ───────────────────────────────────────────────────── */
Canvas2D.prototype.save = function () {
    if (this._stack.length >= this._stackMax) return;
    this._stack.push({
        fillStyle: this.fillStyle,
        strokeStyle: this.strokeStyle,
        lineWidth: this.lineWidth,
        lineCap: this.lineCap,
        lineJoin: this.lineJoin,
        globalAlpha: this.globalAlpha,
        font: this.font,
        textAlign: this.textAlign,
        textBaseline: this.textBaseline,
        shadowColor: this.shadowColor,
        shadowBlur: this.shadowBlur,
        lineDashOffset: this.lineDashOffset,
        dash: this._dash.slice(0)
    });
    try { this._canvas.save(); } catch (e) {}
};

Canvas2D.prototype.restore = function () {
    if (this._stack.length === 0) return;
    var s = this._stack.pop();
    this.fillStyle = s.fillStyle;
    this.strokeStyle = s.strokeStyle;
    this.lineWidth = s.lineWidth;
    this.lineCap = s.lineCap;
    this.lineJoin = s.lineJoin;
    this.globalAlpha = s.globalAlpha;
    this.font = s.font;
    this.textAlign = s.textAlign;
    this.textBaseline = s.textBaseline;
    this.shadowColor = s.shadowColor;
    this.shadowBlur = s.shadowBlur;
    this.lineDashOffset = s.lineDashOffset;
    this._dash = s.dash;
    try { this._canvas.restore(); } catch (e) {}
};

/* ── 变换 ─────────────────────────────────────────────────────── */
Canvas2D.prototype.translate = function (x, y) {
    try { this._canvas.translate(x, y); } catch (e) {}
};
Canvas2D.prototype.rotate = function (rad) {
    try {
        this._canvas.rotate(rad * 180 / Math.PI);
    } catch (e) {}
};
Canvas2D.prototype.scale = function (sx, sy) {
    try { this._canvas.scale(sx, sy); } catch (e) {}
};
Canvas2D.prototype.setTransform = function (a, b, c, d, e, f) {
    /* no-op（2026-11-10）
     *
     * dashboard.js 只在 draw() 开头调 ctx.setTransform(dpr,0,0,dpr,0,0)
     * 把矩阵重置成单位（dpr 恒为 1）。
     *
     * 原来用 canvas.restore() + save() 来"重置"，但会破坏
     * save/restore 配平（dashboard.js 有 75 save / 78 restore），
     * 导致后面的绘制全部画到错误的变换下 —— 表现就是
     * 卡片没背景、圆盘弧不见、路网被放大。
     *
     * 我们的画布本来就是单位矩阵，直接忽略即可。
     */
};

/* ── 路径 ─────────────────────────────────────────────────────── */
Canvas2D.prototype.beginPath = function () {
    try { this._path.reset(); } catch (e) {}
    this._hasPath = true;
};
Canvas2D.prototype.closePath = function () {
    try { this._path.close(); } catch (e) {}
};
Canvas2D.prototype.moveTo = function (x, y) {
    try { this._path.moveTo(x, y); } catch (e) {}
};
Canvas2D.prototype.lineTo = function (x, y) {
    try { this._path.lineTo(x, y); } catch (e) {}
};
Canvas2D.prototype.quadraticCurveTo = function (cx, cy, x, y) {
    try { this._path.quadTo(cx, cy, x, y); } catch (e) {}
};
Canvas2D.prototype.bezierCurveTo = function (c1x, c1y, c2x, c2y, x, y) {
    try { this._path.cubicTo(c1x, c1y, c2x, c2y, x, y); } catch (e) {}
};
Canvas2D.prototype.rect = function (x, y, w, h) {
    try { this._path.addRect(x, y, x + w, y + h, 0); } catch (e) {}
};

/**
 * arc(cx, cy, r, a0, a1, ccw)
 *
 * Canvas 2D 的角度是弧度，0° 在 3 点钟方向，顺时针为正。
 * Android 的 Path.arcTo(RectF, startAngle, sweepAngle) 用度，同样的约定。
 *
 * 但有个关键差异：
 *   Canvas 的 arc 是"把当前点连到弧起点再画弧"
 *   Android 的 arcTo 也是，但标志位不同
 * 这里用 addArc（不连线）还是 arcTo（连线）取决于是否有当前点。
 */
Canvas2D.prototype.arc = function (cx, cy, r, a0, a1, ccw) {
    try {
        this._rectF.set(cx - r, cy - r, cx + r, cy + r);
        var startDeg = a0 * 180 / Math.PI;
        var endDeg = a1 * 180 / Math.PI;
        var sweepDeg = endDeg - startDeg;
        if (ccw) sweepDeg = -Math.abs(sweepDeg);
        else if (sweepDeg < 0) sweepDeg += 360;

        this._path.addArc(this._rectF, startDeg, sweepDeg);
    } catch (e) {}
};

Canvas2D.prototype.arcTo = function (x1, y1, x2, y2, r) {
    try {
        this._path.arcTo(x1, y1, x2, y2, r);
    } catch (e) {}
};

Canvas2D.prototype.ellipse = function (cx, cy, rx, ry, rot, a0, a1, ccw) {
    try {
        this._rectF.set(cx - rx, cy - ry, cx + rx, cy + ry);
        var startDeg = a0 * 180 / Math.PI;
        var sweepDeg = (a1 - a0) * 180 / Math.PI;
        if (ccw) sweepDeg = -Math.abs(sweepDeg);
        this._path.addArc(this._rectF, startDeg, sweepDeg);
    } catch (e) {}
};

/* ── 应用样式到 Paint ────────────────────────────────────────── */
Canvas2D.prototype._applyPaint = function (forStroke, styleStr) {
    var p = this._paint;
    var step = "init";
    try {
        step = "reset";       p.reset();
        step = "antiAlias";   p.setAntiAlias(true);

        /* 渐变防色带（dither）
         *
         * dashboard.js 的背景是三层渐变（bgA→bgB→bgC），
         * 8 位色深下不开 dither 会出现一圈一圈的色带。
         */
        step = "dither";
        try {
            if (typeof p.setDither === 'function') p.setDither(true);
        } catch (eDither) {}

        /* 位图缩放走双线性 */
        step = "filterBitmap";
        try {
            if (typeof p.setFilterBitmap === 'function') p.setFilterBitmap(true);
        } catch (eFilter) {}

        step = "setStyle";    p.setStyle(forStroke ? this._Style.STROKE.value : this._Style.FILL.value);
        step = "strokeWidth"; p.setStrokeWidth(this.lineWidth);

        var cap = this._Cap.BUTT.value;
        if (this.lineCap === "round") cap = this._Cap.ROUND.value;
        else if (this.lineCap === "square") cap = this._Cap.SQUARE.value;
        step = "strokeCap";   p.setStrokeCap(cap);

        var join = this._Join.MITER.value;
        if (this.lineJoin === "round") join = this._Join.ROUND.value;
        else if (this.lineJoin === "bevel") join = this._Join.BEVEL.value;
        step = "strokeJoin";  p.setStrokeJoin(join);

        var cs = forStroke ? this.strokeStyle : (styleStr || this.fillStyle);
        if (cs instanceof C2DGradient) {
            step = "shader";  p.setShader(cs.shaderFor());
        } else {
            var c = c2dParseColor(cs);
            var a = (c >>> 24) & 0xFF;
            var ga = Math.round(a * this.globalAlpha);
            var c2 = ((ga & 0xFF) << 24) | (c & 0x00FFFFFF);
            step = "setColor"; p.setColor(c2 | 0);
        }
    } catch (e) {
        if (!this._paintErrShown) {
            this._paintErrShown = true;
            log("C2D: applyPaint 失败 @" + step + " :: " + String(e).substring(0, 90));
        }
    }
};

Canvas2D.prototype.setLineDash = function (arr) {
    this._dash = (arr && arr.length) ? Array.prototype.slice.call(arr) : [];
};

/* ── 光晕：多次描边模拟 Skia 的 shadowBlur ─────────────────────
 *
 * 背景：Android 的 Paint.setShadowLayer 对「描边路径」几乎不起作用
 * （它本质是给 glyph / 填充加的），而 dashboard.js 大量用光晕来
 * 画圆盘弧线和刻度环（blur 10~14）。
 *
 * 做法：同一路径先画一层宽而淡的外圈，再正常描边。两层足够出柔光感。
 *
 * ★ 性能（2026-11-10 实测）：原来 N=3，每帧 29 个带光晕的 stroke
 *   → 29×4 = 116 次 drawPath，单帧 draw 高达 558ms。
 *   降到 N=1（外圈一次 + 正常一次 = 2 次 drawPath）后
 *   绘制调用量降到 58 次，画质差别在车上几乎看不出（光晕本来就淡）。
 */
Canvas2D.prototype._strokeWithGlow = function (N) {
    var p = this._paint;
    var blur = this.shadowBlur;
    if (!(blur > 0)) return false;

    var sc = c2dParseColor(this.shadowColor);
    var baseA = (sc >>> 24) & 0xFF;
    if (baseA <= 0) return false;

    var rgb = sc & 0x00FFFFFF;
    var baseW = this.lineWidth;
    N = N || 1;

    /* 由外向内：宽 → 窄，淡 → 浓 */
    for (var k = N; k >= 1; k--) {
        var t = k / (N + 0.35);                 /* 外圈权重，别太淡 */
        var w = baseW + blur * 1.6 * t;
        var a = Math.round(baseA * (1 - t) * 0.62);
        if (a <= 0) continue;
        p.setStyle(this._Style.STROKE.value);
        p.setStrokeWidth(w);
        p.setColor(((a << 24) | rgb) | 0);
        this._canvas.drawPath(this._path, p);
    }

    /* 最后把颜色/线宽还原，交给正常描边 */
    p.setStrokeWidth(baseW);
    return true;
};

/* ── 绘制 ─────────────────────────────────────────────────────── */
Canvas2D.prototype.stroke = function () {
    try {
        this._applyPaint(true);

        /* 有光晕且是描边路径 → 先铺一层柔光（1 遍，够用且省一半调用） */
        this._strokeWithGlow(1);

        this._canvas.drawPath(this._path, this._paint);
        this.stats.draws++;
    } catch (e) {}
};

Canvas2D.prototype.fill = function () {
    try {
        this._applyPaint(false);
        this._canvas.drawPath(this._path, this._paint);
        this.stats.draws++;
    } catch (e) {}
};

Canvas2D.prototype.fillRect = function (x, y, w, h) {
    try {
        this._applyPaint(false);
        this._canvas.drawRect(x, y, x + w, y + h, this._paint);
        this.stats.draws++;
    } catch (e) {}
};

Canvas2D.prototype.clearRect = function (x, y, w, h) {
    try {
        var PorterDuff = Java.use('android.graphics.PorterDuff');
        var PorterDuffXfermode = Java.use('android.graphics.PorterDuffXfermode');
        this._paint.reset();
        this._paint.setXfermode(PorterDuffXfermode.$new(PorterDuff.Mode.CLEAR.value));
        this._canvas.drawRect(x, y, x + w, y + h, this._paint);
        this._paint.setXfermode(null);
    } catch (e) {}
};

Canvas2D.prototype.clip = function () {
    try { this._canvas.clipPath(this._path); } catch (e) {}
};

/* ── 文字 ─────────────────────────────────────────────────────── */

/* 解析 letterSpacing。
 *
 * dashboard.js 里是 CSS 写法：ctx.letterSpacing = "3.6px"
 * 也可能是纯数字 "3.6"。取不到就返回 0。
 */
Canvas2D.prototype._lsPx = function () {
    var v = this.letterSpacing;
    if (v === null || v === undefined) return 0;
    if (typeof v === 'number') return v;
    var n = parseFloat(String(v));
    return isNaN(n) ? 0 : n;
};

/* 把字距设到 Paint 上。
 *
 * Android 的 setLetterSpacing 要的是「相对字号的倍数」（em），
 * 不是像素。所以 px / textSize。/ 返回 true 表示原生支持。
 */
Canvas2D.prototype._applyLetterSpacing = function (p, textPx) {
    var px = this._lsPx();
    if (!px || !(textPx > 0)) return true;

    try {
        if (typeof p.setLetterSpacing === 'function') {
            p.setLetterSpacing(px / textPx);
            return true;
        }
    } catch (e) {}
    return false;
};

Canvas2D.prototype.measureText = function (s) {
    try {
        var f = c2dParseFont(this.font);
        this._paint.reset();
        this._paint.setTextSize(f.px);
        this._paint.setTypeface(f.bold ? this._typefaceBold : this._typeface);

        var str = String(s);
        var w = this._paint.measureText(str);

        /* 字距补偿：浏览器把字距算进宽度，Android 原生 API 不一定算 */
        var ls = this._lsPx();
        if (ls && !this._applyLetterSpacing(this._paint, f.px)) {
            w += ls * Math.max(0, str.length - 1);
        }

        return { width: w, actualBoundingBoxLeft: 0, actualBoundingBoxRight: w };
    } catch (e) {
        return { width: 0 };
    }
};

/* 手动逐字绘制（原生 setLetterSpacing 不可用时兜底） */
Canvas2D.prototype._drawTextSpaced = function (s, x, y, p, ls) {
    var str = String(s);
    var total = 0, i;
    for (i = 0; i < str.length; i++) total += p.measureText(str.charAt(i));
    total += ls * Math.max(0, str.length - 1);

    /* 起始 x 按对齐方式回退 */
    var startX = x;
    if (this.textAlign === 'center') startX = x - total / 2;
    else if (this.textAlign === 'right' || this.textAlign === 'end') startX = x - total;

    /* 逐字画的时候必须用 LEFT，否则每个字都被居中 */
    p.setTextAlign(this._Align.LEFT.value);
    var cx = startX;
    for (i = 0; i < str.length; i++) {
        var ch = str.charAt(i);
        this._canvas.drawText(ch, cx, y, p);
        cx += p.measureText(ch) + ls;
    }
};

Canvas2D.prototype.fillText = function (s, x, y, maxW) {
    try {
        var f = c2dParseFont(this.font);
        var p = this._paint;
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(this._Style.FILL.value);
        p.setTextSize(f.px);
        p.setTypeface(f.bold ? this._typefaceBold : this._typeface);

        /* 字距：优先原生，不支持则退回逐字绘制 */
        var lsNative = this._applyLetterSpacing(p, f.px);
        var lsPx = lsNative ? 0 : this._lsPx();

        /* 对齐 */
        var al = this._Align.LEFT.value;
        if (this.textAlign === 'center') al = this._Align.CENTER.value;
        else if (this.textAlign === 'right' || this.textAlign === 'end') al = this._Align.RIGHT.value;
        p.setTextAlign(al);

        /* 基线 */
        /* （原来这里有一句 var BaseAlign = Java.use('android.graphics.Paint$Align');
         *  但它从未被使用 —— 每次 fillText 都白查一次类。
         *  dashboard.js 每帧 52 次 fillText，等于每帧 52 次无用 Java.use。
         *  2026-11-10 删除。 */
        /* Android 的 drawText 默认就是 alphabetic baseline */
        var dy = 0;
        if (this.textBaseline === 'top') {
            dy = p.getFontMetrics().ascent;       /* ascent 是负数 */
        } else if (this.textBaseline === 'middle') {
            var fm = p.getFontMetrics();
            dy = (fm.ascent + fm.descent) / 2;
        } else if (this.textBaseline === 'bottom') {
            dy = p.getFontMetrics().descent;
        }

        /* 颜色 */
        var cs = this.fillStyle;
        if (cs instanceof C2DGradient) {
            p.setShader(cs.shaderFor());
        } else {
            var c = c2dParseColor(cs);
            var a = (c >>> 24) & 0xFF;
            var ga = Math.round(a * this.globalAlpha);
            p.setColor((((ga & 0xFF) << 24) | (c & 0x00FFFFFF)) | 0);
        }

        /* 阴影（用 setShadowLayer，Android 原生支持） */
        if (this.shadowBlur > 0) {
            try {
                var sc = c2dParseColor(this.shadowColor);
                p.setShadowLayer(this.shadowBlur, 0, 0, sc);
            } catch (e) {}
        }

        /* 有字距但原生不支持 → 逐字绘制 */
        if (lsPx) {
            this._drawTextSpaced(s, x, y + dy, p, lsPx);
        } else {
            this._canvas.drawText(String(s), x, y + dy, p);
        }
        this.stats.draws++;

        /* 清掉 shader / shadow，避免影响下一次 */
        p.setShader(null);
        p.setShadowLayer(0, 0, 0, 0);    } catch (e) {
        if (!this._textErrShown) {
            this._textErrShown = true;
            log('C2D: fillText 失败 ' + String(e).substring(0, 80));
        }
    }
};

/* ── 渐变 ─────────────────────────────────────────────────────── */

/**
 * createLinearGradient(x0, y0, x1, y1)
 *
 * Android 的 LinearGradient 需要颜色数组，但 Canvas 是"先建对象再 addColorStop"。
 * 所以这里延迟创建：先返回一个空壳，addColorStop 收集颜色，真正 setShader 时才建。
 */
Canvas2D.prototype.createLinearGradient = function (x0, y0, x1, y1) {
    return new C2DGradient(1, x0, y0, x1, y1, 0);
};

Canvas2D.prototype.createRadialGradient = function (x0, y0, r0, x1, y1, r1) {
    return new C2DGradient(2, x1, y1, x1, y1, r1);
};

/* ── 每帧提交 ─────────────────────────────────────────────────── */
Canvas2D.prototype.flush = function () {
    this.stats.frames++;
};

Canvas2D.prototype.reset = function () {
    try {
        this._path.reset();
    } catch (e) {}
    this._stack = [];
};

/* ── 诊断 ─────────────────────────────────────────────────────── */
Canvas2D.prototype.status = function () {
    return 'ready=' + this._ready
         + ' frames=' + this.stats.frames
         + ' draws=' + this.stats.draws
         + (this._err ? ' err=' + this._err : '');
};

/* ===== dashboard.js ===== */
/* ============================================================================
   dashboard.js  —  1920 × 538 车机仪表盘 · 纯 Canvas 2D 渲染
   ----------------------------------------------------------------------------
   只读仪表：不接受任何鼠标 / 触摸输入，画面 100% 由 setData() 驱动。
   完整数据字典（字段 / 单位 / 范围 / 触发规则 / 接入示例）见同目录
   《仪表盘交接文档.md》——改字段前先对照那份表。

   画面分区（设计稿坐标）：
     速度卡   16,16   512×314   圆盘 cx272 cy150 r120，量程 0–220，超 120 整盘转红
     电量卡   16,342  512×84    低于 20% 转黄
     燃油卡   16,438  512×84    低于 20% 转红
     灯光栏   544,16  864×40    7 个灯位：左转/自动大灯/近光/远光/自动驻车/后雾/右转
     地图     544,64  864×382   路线 + 三车道 + 自车 + 车道引导
     横条     544,458 864×64    导航 / 行程 / 能耗 / 胎压 四种内容
     时钟     1424,16 480×92    取系统时间，无需喂数据
     媒体     1424,120 480×326  播放态（唱片+歌词）/ 待机态
     档位     1424,458 480×64   档位字母 + 四个混动模式胶囊

   用法一（自动全屏挂载）：  <script src="dashboard.js"></script>
   用法二（手动挂载，推荐接真实数据）：
        const hud = new HUD(canvas);
        hud.autoSpeed = false;      // 关闭内置模拟，改为跟随真实车速
        hud.autoPower = false;      // 关闭功率模拟
        hud.start();
        hud.setData({ speed: 108, evRange: 130, fuelRange: 1370, power: 142 });
   ========================================================================== */
(function (global) {
  'use strict';

  const W = 1920, H = 538;

  /* ------------------------------ 调色板 ------------------------------ */
  const C = {
    bgA: '#080C0E', bgB: '#05090B', bgC: '#040708',
    teal: '#43E8C8',
    tealLt: '#B6FFF2',
    amber: '#FFB24D',
    amberLt: '#FFD9A3',
    warn: '#FFC93C',      // 电量低
    warnLt: '#FFE7A6',
    red: '#FF5252',       // 燃油低 / 超速
    redLt: '#FFB3B3',
    txt: '#E6F1F1',
    dim: '#75898E',
    dim2: '#5E7277',
    dim3: '#4E6165',
    line: 'rgba(120,170,180,0.14)'
  };

  const FONT = '"Bahnschrift","DIN Alternate","Segoe UI","Microsoft YaHei",sans-serif';
  const f = (size, weight) => `${weight || 400} ${size}px ${FONT}`;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

  /* ------------------------------ 布局 ------------------------------ */
  const PAD = 16, GAP = 16;
  const L = {
    leftX: PAD, leftW: 512,
    gauge: { x: PAD, y: PAD, w: 512, h: 314 },
    evCard: { x: PAD, y: 342, w: 512, h: 84 },
    fuCard: { x: PAD, y: 438, w: 512, h: 84 },
    map: { x: 544, y: 64, w: 864, h: 382 },
    rail: { x: 544, y: PAD, w: 864, h: 40 },
    navStrip: { x: 544, y: 458, w: 864, h: 64 },
    rightX: 1424, rightW: 480,
    clock: { x: 1424, y: PAD, w: 480, h: 92 },
    media: { x: 1424, y: 120, w: 480, h: 326 },
    gear: { x: 1424, y: 458, w: 480, h: 64 }
  };

  /* 速度表几何：0–220，超速阈值 100 */
  const G = { cx: PAD + 256, cy: PAD + 134, r: 120, a0: -122, a1: 122, max: 220, over: 120 };

  /* ------------------------------ 小工具 ------------------------------ */
  function rr(ctx, x, y, w, h, r) {
    r = Math.min(r, Math.abs(w) / 2, Math.abs(h) / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.arcTo(x + w, y, x + w, y + r, r);
    ctx.lineTo(x + w, y + h - r);
    ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
    ctx.lineTo(x + r, y + h);
    ctx.arcTo(x, y + h, x, y + h - r, r);
    ctx.lineTo(x, y + r);
    ctx.arcTo(x, y, x + r, y, r);
    ctx.closePath();
  }

  function setLS(ctx, px) { if ('letterSpacing' in ctx) ctx.letterSpacing = px + 'px'; }

  function arcPath(ctx, cx, cy, r, a0, a1) {
    ctx.beginPath();
    ctx.arc(cx, cy, r, (a0 - 90) * Math.PI / 180, (a1 - 90) * Math.PI / 180, false);
  }

  function polar(cx, cy, r, a) {
    const t = a * Math.PI / 180;
    return [cx + r * Math.sin(t), cy - r * Math.cos(t)];
  }

  /* 颜色插值：#rrggbb × 2 → rgb() */
  function rgba(hex, a) {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return `rgba(${r},${g},${b},${a})`;
  }

  function lerpColor(a, b, t) {
    const p = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
    const A = p(a), B = p(b);
    const h = v => Math.round(v).toString(16).padStart(2, '0');
    return '#' + h(A[0] + (B[0] - A[0]) * t) + h(A[1] + (B[1] - A[1]) * t) + h(A[2] + (B[2] - A[2]) * t);
  }

  /* 折线：长度 / 采样 / 点 / 切线 */
  function polyMeta(pts) {
    const cum = [0];
    let len = 0;
    for (let i = 1; i < pts.length; i++) {
      len += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
      cum.push(len);
    }
    return { pts, cum, len };
  }
  function polyAt(m, u) {
    const d = clamp(u, 0, 1) * m.len;
    let lo = 0, hi = m.cum.length - 1;
    while (lo < hi - 1) {
      const mid = (lo + hi) >> 1;
      if (m.cum[mid] <= d) lo = mid; else hi = mid;
    }
    const seg = m.cum[hi] - m.cum[lo] || 1;
    const t = (d - m.cum[lo]) / seg;
    const a = m.pts[lo], b = m.pts[hi];
    return {
      x: a[0] + (b[0] - a[0]) * t,
      y: a[1] + (b[1] - a[1]) * t,
      ang: Math.atan2(b[1] - a[1], b[0] - a[0])
    };
  }
  function tracePoly(ctx, m) {
    ctx.beginPath();
    ctx.moveTo(m.pts[0][0], m.pts[0][1]);
    for (let i = 1; i < m.pts.length; i++) ctx.lineTo(m.pts[i][0], m.pts[i][1]);
  }

  /* 沿折线法线方向偏移，用于生成车道边界 */
  function offsetPoly(pts, d) {
    const out = [];
    for (let i = 0; i < pts.length; i++) {
      const a = pts[Math.max(0, i - 1)];
      const b = pts[Math.min(pts.length - 1, i + 1)];
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const len = Math.hypot(dx, dy) || 1;
      out.push([pts[i][0] - dy / len * d, pts[i][1] + dx / len * d]);
    }
    return out;
  }

  function tracePts(ctx, pts) {
    ctx.beginPath();
    ctx.moveTo(pts[0][0], pts[0][1]);
    for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
  }
  function sampleBezier(segs, per) {
    per = per || 26;
    const out = [];
    let cur = [0, 0];
    for (const s of segs) {
      if (s.t === 'M' || s.t === 'L') { cur = s.p.slice(); out.push(cur.slice()); }
      else if (s.t === 'C') {
        const [x1, y1, x2, y2, x3, y3] = s.p;
        for (let i = 1; i <= per; i++) {
          const t = i / per, u = 1 - t;
          out.push([
            u * u * u * cur[0] + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
            u * u * u * cur[1] + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
          ]);
        }
        cur = [x3, y3];
      }
    }
    return out;
  }

  /* ------------------------------ 地图素材 ------------------------------ */
  const ROUTE_SEGS = [
    { t: 'M', p: [262, 470] },
    { t: 'C', p: [262, 408, 338, 394, 392, 358] },
    { t: 'C', p: [452, 316, 470, 302, 562, 294] },
    { t: 'C', p: [660, 284, 706, 238, 764, 188] },
    { t: 'L', p: [842, 128] }
  ];
  const ROUTE = polyMeta(sampleBezier(ROUTE_SEGS, 30));
  const HIGHWAY = polyMeta([[-40, 512], [250, 358], [520, 320], [772, 208], [1080, 80]]);
  const TRAFFIC = [
    { m: polyMeta([[-30, 296], [1070, 296]]), dur: 11, c: C.teal, u: 0.12 },
    { m: polyMeta([[1070, 96], [-30, 96]]), dur: 16, c: C.amber, u: 0.55 },
    { m: HIGHWAY, dur: 19, c: '#8FB6BC', u: 0.3 }
  ];
  const MINOR_V = [110, 240, 380, 520, 660, 800, 930];
  const MINOR_H = [70, 184, 290, 400, 498];
  const MAJOR_V = [200, 520, 812];
  const MAJOR_H = [96, 296, 430];
  const BLOCKS = [
    [128, 116, 66, 52], [228, 112, 92, 60], [356, 120, 74, 48],
    [452, 330, 86, 56], [560, 128, 64, 60], [828, 228, 88, 54],
    [148, 330, 76, 58], [852, 336, 74, 52]
  ];
  const MAP_LABELS = [
    [612, 292, '中山北路', '#5C7176', -3],
    [246, 92, '环城高架', '#5C7176', -2],
    [126, 424, '滨江大道', '#5C7176', -2],
    [655, 352, '中央公园', '#3E7A63', 0],
    [848, 196, '科技园', '#5C7176', 0],
    [392, 512, '老城区', '#4B5F64', 0]
  ];

  /* ============================================================================
     HUD
     ========================================================================== */
  class HUD {
    constructor(canvas, opts) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.dpr = Math.min(global.devicePixelRatio || 1, 2);

      this.data = Object.assign({
        speed: 0, noa: true, noaLabel: 'NOA', noaSpeed: 120,
        /* 档位 + 混动模式 */
        gear: 'D', driveMode: '纯电',
        modes: ['智能', '纯电', '增程', '性能'],
        /* 续航：value 为当前续航（km），cap 为满值（km），低于 20% 触发告警色 */
        evRange: 130, evCap: 180,
        fuelRange: 1370, fuelCap: 1560,
        /* 总功率 kW */
        power: 142, powerMax: 300,

        /* 车道引导：推荐车道用 active 索引标出 */
        lanes: ['left', 'straight', 'straight', 'straight', 'right'],
        laneActive: [2, 3],

        tripKm: '24.6', tripAvg: '42', tripMin: '35', tripEnergy: '15.8', tripFuel: '6.2',
        navActive: false,
        navEnd: 'exited',           // 导航结束原因：arrived（到达，显示本次行程）| exited（退出，显示胎压）
        tires: [['左前', 2.4], ['右前', 2.4], ['左后', 2.5], ['右后', 2.1]],
        navDist: '300', navUnit: '米', navStreet: '中山北路', navTurn: '右',
        navLeft: '12.4 km', navEta: '09 : 52', navTime: '23 分钟',
        outdoor: '18°', indoor: '22°', weather: '晴 · 东南风 2 级',
        /* 灯光状态：true = 点亮 */
        lights: {
          left: true, auto: true, low: true, high: false,
          hold: true, fogR: false, right: false, hazard: false
        },
        /* 媒体 / 歌词 */
        mediaTitle: '夜航西飞', mediaArtist: '陈明', mediaAlbum: '长夜',
        lyric0: '风把城市 吹得很远',
        lyric1: '云在脚下 慢慢退潮',
        lyric2: '我把夜航 折成一封信',
        lyric3: '而你还在 灯火那边',
        mediaPct: 42, mediaTime: '1:42', mediaDur: '4:05',
        mediaPlaying: false,
      }, opts || {});

      this.animEv = 0;
      this.animFu = 0;
      this.t0 = performance.now();
      this.sim = 0;
      this.pow = 0;
      this.running = false;

      this.time = 0;
      this.last = 0;
      this.autoSpeed = true;
      this.autoPower = true;
      this.noaPulseAt = null;
      this.fps = 0;
      this._frames = 0;
      this._fpsT = 0;

      this.fit = this.fit.bind(this);
      this.loop = this.loop.bind(this);
      this.resize();
      global.addEventListener('resize', this.fit);
      if (global.ResizeObserver && canvas.parentElement) {
        this._ro = new ResizeObserver(() => this.fit());
        this._ro.observe(canvas.parentElement);
      }
    }

    /* 卸载：车机切页 / 热更新时调用，避免监听泄漏 */
    destroy() {
      this.stop();
      if (this._ro) { this._ro.disconnect(); this._ro = null; }
      global.removeEventListener('resize', this.fit);
    }


    resize() {
      const c = this.canvas;
      const d = Math.min(global.devicePixelRatio || 1, 2);
      if (d !== this.dpr) this.dpr = d;
      c.width = Math.round(W * this.dpr);
      c.height = Math.round(H * this.dpr);
      const s = Math.min(global.innerWidth / W, global.innerHeight / H);
      c.style.width = Math.round(W * s) + 'px';
      c.style.height = Math.round(H * s) + 'px';
      c.style.display = 'block';
      if (c.parentElement) {
        c.parentElement.style.display = 'flex';
        c.parentElement.style.alignItems = 'center';
        c.parentElement.style.justifyContent = 'center';
      }
    }
    fit() { this.resize(); }
    setData(d) {
      // NOA 状态变化时触发一次接管闪环
      if (d && 'noa' in d && d.noa !== this.data.noa) this.noaPulseAt = this.time;
      Object.assign(this.data, d);
    }

    start() {
      if (this.running) return;
      this.running = true;
      this.last = performance.now();
      this._fpsT = this.last;
      this._frames = 0;
      requestAnimationFrame(this.loop);
    }
    stop() { this.running = false; }

    loop(now) {
      if (!this.running) return;
      const dt = Math.min((now - this.last) / 1000, 0.1);
      this.last = now;
      this.time += dt;
      const t = this.time;

      this._frames++;
      if (now - this._fpsT >= 500) {
        this.fps = Math.round(this._frames * 1000 / (now - this._fpsT));
        this._frames = 0;
        this._fpsT = now;
      }

      let target;
      if (!this.autoSpeed) target = this.data.speed;
      else if (this.data.speed > 0) target = this.data.speed;
      else target = clamp(96 + Math.sin(t * 0.31) * 34 + Math.sin(t * 1.37) * 9 + Math.sin(t * 2.9) * 3, 18, 196);
      this.sim += (target - this.sim) * 0.055;

      // 总功率：自动模式下跟随车速起伏，锁定模式用真实值
      const pTarget = this.autoPower
        ? clamp(this.sim * 1.18 + 14 + Math.sin(t * 0.9) * 9, 0, this.data.powerMax)
        : this.data.power;
      this.pow += (pTarget - this.pow) * 0.06;

      this.animEv += (1 - this.animEv) * 0.055;
      this.animFu += (1 - this.animFu) * 0.055;

      this.draw(t);
      requestAnimationFrame(this.loop);
    }

    /* ============================ 主绘制 ============================ */
    draw(t) {
      const ctx = this.ctx;
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);

      this.drawBackdrop(ctx);
      this.drawSpeedo(ctx, t);
      this.drawRange(ctx, L.evCard, {
        name: '电量续航', val: this.data.evRange, cap: this.data.evCap,
        anim: this.animEv, kind: 'ev'
      });
      this.drawRange(ctx, L.fuCard, {
        name: '燃油续航', val: this.data.fuelRange, cap: this.data.fuelCap,
        anim: this.animFu, kind: 'fuel'
      });
      this.drawLightRail(ctx, t);
      this.drawMap(ctx, t);
      this.drawClock(ctx);
      this.drawNavStrip(ctx);
      this.drawMedia(ctx, t);
      this.drawGear(ctx);
    }

    drawBackdrop(ctx) {
      const g = ctx.createLinearGradient(0, 0, 0, H);
      g.addColorStop(0, C.bgA); g.addColorStop(0.62, C.bgB); g.addColorStop(1, C.bgC);
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);

      let rg = ctx.createRadialGradient(272, 0, 0, 272, 0, 760);
      rg.addColorStop(0, 'rgba(67,232,200,0.075)'); rg.addColorStop(1, 'rgba(67,232,200,0)');
      ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);

      rg = ctx.createRadialGradient(1664, H, 0, 1664, H, 620);
      rg.addColorStop(0, 'rgba(255,178,77,0.055)'); rg.addColorStop(1, 'rgba(255,178,77,0)');
      ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);
    }

    panel(ctx, x, y, w, h, r) {
      r = r === undefined ? 20 : r;
      const g = ctx.createLinearGradient(x, y, x + w * 0.55, y + h);
      g.addColorStop(0, 'rgba(21,31,36,0.94)');
      g.addColorStop(1, 'rgba(8,13,16,0.97)');
      rr(ctx, x + 0.5, y + 0.5, w - 1, h - 1, r);
      ctx.fillStyle = g; ctx.fill();
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      rr(ctx, x + 1, y + 1, w - 2, h - 2, r - 1);
      ctx.clip();
      const gl = ctx.createLinearGradient(0, y, 0, y + h * 0.42);
      gl.addColorStop(0, 'rgba(255,255,255,0.052)');
      gl.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = gl; ctx.fillRect(x, y, w, h * 0.42);
      ctx.restore();
    }

    /* --------------------------- 速度圆盘 --------------------------- */
    drawSpeedo(ctx, t) {
      const card = L.gauge;
      this.panel(ctx, card.x, card.y, card.w, card.h);

      const { cx, cy, r, a0, a1, max, over } = G;
      const frac = clamp(this.sim / max, 0, 1);
      // 超速程度：0=青绿，1=红
      const hot = clamp((this.sim - over) / 4, 0, 1);

      // 超过阈值时整盘染色
      const colTrack = lerpColor('#13242B', '#341A1C', hot);
      const colMajor = lerpColor('#7A9096', '#C46A6A', hot);
      const colMinor = lerpColor('#33474E', '#5E2C2E', hot);
      const colLabel = lerpColor('#63787D', '#D98C8C', hot);
      const colInner = lerpColor('#8A9A9E', '#B06A6A', hot);

      /* 轨道 */
      arcPath(ctx, cx, cy, r, a0, a1);
      ctx.strokeStyle = colTrack; ctx.lineWidth = 10;
      ctx.lineCap = 'round'; ctx.stroke();

      arcPath(ctx, cx, cy, r - 30, a0, a1);
      ctx.strokeStyle = rgba(colInner, 0.9); ctx.lineWidth = 1; ctx.stroke();

      /* 刻度：每 10 一格，每 20 主线 + 数字 */
      for (let v = 0; v <= max; v += 10) {
        const a = a0 + (a1 - a0) * (v / max);
        const major = v % 20 === 0;
        const p1 = polar(cx, cy, r, a);
        const p2 = polar(cx, cy, major ? r - 18 : r - 10, a);

        const isThr = v === over;
        ctx.beginPath();
        ctx.moveTo(p1[0], p1[1]); ctx.lineTo(p2[0], p2[1]);
        ctx.strokeStyle = isThr ? (hot > 0.5 ? '#FFFFFF' : 'rgba(255,82,82,0.85)') : (major ? colMajor : colMinor);
        ctx.lineWidth = isThr ? 3 : (major ? 2.2 : 1.3);
        ctx.lineCap = 'round';
        if (isThr) { ctx.shadowColor = 'rgba(255,82,82,0.7)'; ctx.shadowBlur = 8; }
        ctx.stroke();
        ctx.shadowBlur = 0;

        if (major) {
          const lp = polar(cx, cy, r - 36, a);
          ctx.save();
          setLS(ctx, 0.4);
          ctx.fillStyle = isThr ? (hot > 0.5 ? '#FFFFFF' : 'rgba(255,140,140,0.95)') : colLabel;
          ctx.font = f(11.5, 400);
          ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText(v, lp[0], lp[1]);
          ctx.restore();
        }
      }

      /* 进度弧：超过阈值整条转红 */
      const aNow = a0 + (a1 - a0) * frac;

      if (frac > 0) {
        const grad = ctx.createLinearGradient(cx - r, cy + r, cx + r, cy - r);
        grad.addColorStop(0, lerpColor('#1E8E7C', '#C43C3C', hot));
        grad.addColorStop(0.55, lerpColor(C.teal, '#FF7B7B', hot));
        grad.addColorStop(1, lerpColor('#B6FFF2', '#FFD9D9', hot));
        ctx.save();
        ctx.shadowColor = hot > 0.5 ? 'rgba(255,82,82,0.62)' : rgba(C.teal, 0.5);
        ctx.shadowBlur = 14;
        arcPath(ctx, cx, cy, r, a0, aNow);
        ctx.strokeStyle = grad;
        ctx.lineWidth = 10; ctx.lineCap = 'round';
        ctx.stroke();
        ctx.restore();
      }

      /* NOA 外环：底环 + 缓转扫描带 + 跟随指针的彗尾 + 接管闪环 */
      if (this.data.noa !== false) {
        const ringR = r + 12;
        const ringHex = hot > 0.5 ? C.red : C.teal;

        // 底环
        arcPath(ctx, cx, cy, ringR, a0, a1);
        ctx.strokeStyle = rgba(C.teal, 0.10);
        ctx.lineWidth = 2.5;
        ctx.lineCap = 'round';
        ctx.stroke();

        // 雷达扫描带：9 段递减排布，逆时针缓转
        const span = a1 - a0;
        const pos = a1 - ((t * 15) % span);
        for (let i = 0; i < 9; i++) {
          const s0 = pos + i * 11;
          if (s0 >= a1) break;
          arcPath(ctx, cx, cy, ringR, s0, Math.min(a1, s0 + 11));
          ctx.strokeStyle = rgba(ringHex, 0.16 * (1 - i / 9));
          ctx.lineWidth = 2.5;
          ctx.stroke();
        }

        // 彗尾
        if (aNow - a0 > 4) {
          const tail = Math.max(a0, aNow - 30);
          ctx.save();
          ctx.shadowColor = rgba(ringHex, 0.55);
          ctx.shadowBlur = 10;
          arcPath(ctx, cx, cy, ringR, tail, aNow);
          ctx.strokeStyle = rgba(ringHex, 0.62);
          ctx.lineWidth = 2.5;
          ctx.lineCap = 'round';
          ctx.stroke();
          ctx.restore();

          const hp = polar(cx, cy, ringR, aNow);
          ctx.save();
          ctx.beginPath();
          ctx.arc(hp[0], hp[1], 2.6, 0, 6.2832);
          ctx.fillStyle = ringHex;
          ctx.shadowColor = rgba(ringHex, 0.9);
          ctx.shadowBlur = 10;
          ctx.fill();
          ctx.restore();
        }

        // 设定车速标记（打断底环的小圆点）
        const setA = a0 + (a1 - a0) * ((this.data.noaSpeed || 120) / max);
        const sp2 = polar(cx, cy, ringR, setA);
        ctx.beginPath(); ctx.arc(sp2[0], sp2[1], 5, 0, 6.2832);
        ctx.fillStyle = '#0A1114'; ctx.fill();
        ctx.beginPath(); ctx.arc(sp2[0], sp2[1], 3, 0, 6.2832);
        ctx.fillStyle = hot > 0.5 ? C.red : C.teal; ctx.fill();

        // 接管瞬间：整圈闪一下（0.45s 衰减）
        if (this.noaPulseAt != null) {
          const p = (this.time - this.noaPulseAt) / 0.45;
          if (p >= 1) {
            this.noaPulseAt = null;
          } else {
            ctx.save();
            arcPath(ctx, cx, cy, ringR, a0, a1);
            ctx.strokeStyle = rgba(C.teal, 0.6 * (1 - p));
            ctx.lineWidth = 3.5;
            ctx.shadowColor = rgba(C.teal, 0.8 * (1 - p));
            ctx.shadowBlur = 14;
            ctx.stroke();
            ctx.restore();
          }
        }
      }

      /* 指针 */
      const nTip = lerpColor('#EAFFFA', '#FFE2E2', hot);
      const nMid = lerpColor(C.teal, C.red, hot);
      const nBase = lerpColor('#123A38', '#3A1414', hot);
      const ang = aNow;
      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(ang * Math.PI / 180);
      ctx.shadowColor = hot > 0.5 ? 'rgba(255,82,82,0.6)' : 'rgba(67,232,200,0.5)';
      ctx.shadowBlur = 12;
      const ng = ctx.createLinearGradient(0, -r + 6, 0, 20);
      ng.addColorStop(0, nTip); ng.addColorStop(0.5, nMid); ng.addColorStop(1, nBase);
      ctx.beginPath();
      ctx.moveTo(0, -r + 6);
      ctx.lineTo(5.4, -6); ctx.lineTo(2.6, 20); ctx.lineTo(-2.6, 20); ctx.lineTo(-5.4, -6);
      ctx.closePath();
      ctx.fillStyle = ng; ctx.fill();
      ctx.restore();

      /* 轴心 */
      ctx.beginPath(); ctx.arc(cx, cy, 11, 0, 6.2832);
      ctx.fillStyle = '#0A1114'; ctx.fill();
      ctx.strokeStyle = hot > 0.5 ? '#5A2020' : '#2B424A'; ctx.lineWidth = 1.2; ctx.stroke();
      ctx.beginPath(); ctx.arc(cx, cy, 4, 0, 6.2832);
      ctx.fillStyle = lerpColor(C.teal, C.red, hot); ctx.fill();
      ctx.beginPath(); ctx.arc(cx, cy, 1.6, 0, 6.2832);
      ctx.fillStyle = '#0A1114'; ctx.fill();

      /* 数字车速 */
      ctx.save();
      ctx.textAlign = 'center';
      ctx.shadowColor = hot > 0.5 ? 'rgba(255,110,110,0.45)' : 'rgba(200,255,245,0.35)';
      ctx.shadowBlur = 16;
      ctx.fillStyle = hot > 0.5 ? '#FF9E9E' : '#F0FBFA';
      ctx.font = f(62, 600);
      setLS(ctx, -2);
      ctx.fillText(String(Math.round(this.sim)), cx, 248);
      ctx.restore();

      ctx.save();
      ctx.textAlign = 'center';
      ctx.fillStyle = hot > 0.5 ? '#B06060' : '#6B8085';
      ctx.font = f(11, 400);
      setLS(ctx, 3.6);
      ctx.fillText('km / h', cx, 271);
      ctx.restore();

      /* 智驾模式 + 设定车速 + 加减：排在同一行 */
      const noaOn = this.data.noa !== false;
      const setSpd = this.data.noaSpeed != null ? this.data.noaSpeed : 120;
      const noaCol = noaOn ? C.teal : '#E4EFEF';   // 未启用时为白色

      /* 状态点：激活时缓慢呼吸 */
      ctx.save();
      ctx.beginPath();
      ctx.arc(card.x + 11, 302, 3, 0, 6.2832);
      if (noaOn) {
        const br = 0.55 + 0.45 * Math.sin(t * 2.2);
        ctx.globalAlpha = br;
        ctx.fillStyle = C.teal;
        ctx.shadowColor = rgba(C.teal, 0.9);
        ctx.shadowBlur = 10;
      } else {
        ctx.fillStyle = 'rgba(230,241,241,0.42)';
      }
      ctx.fill();
      ctx.restore();

      /* NOA 字标 */
      const labelTxt = this.data.noaLabel || 'NOA';
      ctx.save();
      ctx.textAlign = 'left';
      ctx.fillStyle = noaCol;
      ctx.font = f(15, 600);
      setLS(ctx, 2.2);
      if (noaOn) {
        ctx.shadowColor = rgba(C.teal, 0.55);
        ctx.shadowBlur = 8;
      }
      ctx.fillText(labelTxt, card.x + 22, 307);
      const labelW = ctx.measureText(labelTxt).width;
      ctx.restore();

      /* 设定速度方牌：NOA 亮则亮，灭则灭 */
      const bdgX = card.x + 22 + labelW + 10;
      const bdgY = 289, bdgW = 40, bdgH = 26;

      ctx.save();
      rr(ctx, bdgX, bdgY, bdgW, bdgH, 9);
      if (noaOn) {
        const fg = ctx.createLinearGradient(0, bdgY, 0, bdgY + bdgH);
        fg.addColorStop(0, rgba(C.teal, 0.24));
        fg.addColorStop(1, rgba(C.teal, 0.06));
        ctx.fillStyle = fg;
      } else {
        const fg = ctx.createLinearGradient(0, bdgY, 0, bdgY + bdgH);
        fg.addColorStop(0, 'rgba(235,245,245,0.08)');
        fg.addColorStop(1, 'rgba(235,245,245,0.02)');
        ctx.fillStyle = fg;
      }
      ctx.fill();

      ctx.strokeStyle = noaOn ? rgba(C.teal, 0.9) : 'rgba(232,242,242,0.42)';
      ctx.lineWidth = 1.5;
      if (noaOn) {
        ctx.shadowColor = rgba(C.teal, 0.6);
        ctx.shadowBlur = 10;
      }
      ctx.stroke();
      ctx.restore();

      /* 内顶高光：让牌面有一点厚度 */
      ctx.save();
      rr(ctx, bdgX, bdgY, bdgW, bdgH, 9);
      ctx.clip();
      const hl = ctx.createLinearGradient(0, bdgY, 0, bdgY + 8);
      hl.addColorStop(0, noaOn ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.10)');
      hl.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = hl;
      ctx.fillRect(bdgX, bdgY, bdgW, 8);
      ctx.restore();

      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = noaOn ? '#E8FFFA' : '#DDE9E9';
      if (noaOn) {
        ctx.shadowColor = rgba(C.teal, 0.7);
        ctx.shadowBlur = 7;
      }
      ctx.font = f(15, 600);
      setLS(ctx, 0.6);
      ctx.fillText(String(setSpd), bdgX + bdgW / 2, bdgY + bdgH / 2 + 0.5);
      ctx.restore();

      ctx.save();
      const lx0 = 466, ly0 = 302, lr = 18;
      ctx.beginPath(); ctx.arc(lx0, ly0, lr, 0, 6.2832);
      ctx.fillStyle = 'rgba(12,19,22,0.92)'; ctx.fill();
      ctx.strokeStyle = 'rgba(224,72,64,0.8)';
      ctx.lineWidth = 2.6; ctx.stroke();
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillStyle = '#EAF6F6';
      ctx.font = f(12, 600);
      setLS(ctx, 0.5);
      ctx.fillText('120', lx0, ly0 + 0.5);
      ctx.restore();

      /* 总功率 —— 温度计样式 */
      this.drawPower(ctx, t);
    }

    /* --------------------------- 总功率温度计 --------------------------- */
    drawPower(ctx, t) {
      const { cx } = G;
      const x0 = cx - 132, x1 = cx + 132;
      const val = clamp(this.pow, 0, this.data.powerMax);
      const frac = val / this.data.powerMax;

      // 标题 + 读数
      ctx.save();
      ctx.textBaseline = 'alphabetic';
      ctx.fillStyle = C.dim2;
      ctx.font = f(9.5, 400);
      setLS(ctx, 2.4);
      ctx.textAlign = 'left';
      ctx.fillText('总功率', x0, 288);

      ctx.textAlign = 'right';
      ctx.font = f(10.5, 500);
      setLS(ctx, 1);
      ctx.fillStyle = frac > 0.85 ? C.redLt : '#CFE1E1';
      ctx.fillText(Math.round(val) + ' kW', x1, 288);
      ctx.restore();

      // 温度计主体：左侧球泡 + 右侧管
      const ty = 296, th = 12, bulbR = 10;
      const bx = x0 + 6, byy = ty + th / 2;

      // 槽体
      ctx.save();
      rr(ctx, bx + 8, ty, x1 - bx - 8, th, th / 2);
      ctx.fillStyle = '#0F191D'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,170,180,0.18)'; ctx.lineWidth = 1; ctx.stroke();

      ctx.beginPath(); ctx.arc(bx, byy, bulbR, 0, 6.2832);
      ctx.fillStyle = '#0F191D'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,170,180,0.18)'; ctx.stroke();
      ctx.restore();

      // 填充：整条渐变按位置取色（青 → 黄 → 红）
      const tubeL = bx + 8, tubeR = x1;
      const totalW = tubeR - tubeL;
      const grad = ctx.createLinearGradient(tubeL, 0, tubeR, 0);
      grad.addColorStop(0, '#2FBFA8');
      grad.addColorStop(0.45, C.warn);
      grad.addColorStop(0.78, '#FF7A45');
      grad.addColorStop(1, C.red);

      const fw = totalW * frac;
      if (fw > 0.8) {
        ctx.save();
        rr(ctx, tubeL, ty + 1, totalW, th - 2, (th - 2) / 2);
        ctx.clip();
        ctx.shadowColor = 'rgba(255,140,80,0.35)';
        ctx.shadowBlur = 10;
        ctx.fillStyle = grad;
        ctx.fillRect(tubeL, ty, fw, th);
        ctx.restore();

        // 球泡充能
        ctx.save();
        ctx.beginPath(); ctx.arc(bx, byy, bulbR - 2.5, 0, 6.2832);
        ctx.fillStyle = '#2FBFA8';
        ctx.shadowColor = 'rgba(47,191,168,0.5)'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.restore();
      }

      // 液面端点高光
      if (fw > 2) {
        const lx = tubeL + fw;
        ctx.save();
        ctx.beginPath(); ctx.arc(lx, byy, 2.4, 0, 6.2832);
        ctx.fillStyle = '#FFFFFF'; ctx.globalAlpha = 0.9;
        ctx.shadowColor = '#FFF'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.restore();
      }

      // 刻线：25 / 50 / 75 %
      ctx.save();
      for (let i = 1; i <= 3; i++) {
        const tx = tubeL + totalW * (i / 4);
        ctx.beginPath();
        ctx.moveTo(tx, ty + 2);
        ctx.lineTo(tx, ty + th - 2);
        ctx.strokeStyle = 'rgba(0,0,0,0.35)';
        ctx.lineWidth = 1;
        ctx.stroke();
      }
      ctx.restore();
    }

    /* --------------------------- 续航卡 --------------------------- */
    drawRange(ctx, card, o) {
      this.panel(ctx, card.x, card.y, card.w, card.h);

      const x = card.x + 18, right = card.x + card.w - 18, w = card.w - 36;
      const ratio = clamp(o.val / o.cap, 0, 1);
      const low = ratio < 0.2;
      const pct = Math.round(ratio * 100);

      /* 颜色规则：
         常态按「能量类型」分色相——电量 >20% 绿 #5CE38A，燃油 >20% 蓝 #4FC3FF
         告警沿用既定规则——电量 <20% 黄 #FFC93C、<10% 转红；燃油 <20% 直接转红
         条用同色相明度渐变，避免跨色相渐变造成的浑浊 */
      let main, light, g0, g1;
      const critical = ratio < 0.1;
      if (o.kind === 'ev') {
        if (critical) { main = C.red; light = C.redLt; g0 = '#8E2626'; g1 = C.red; }
        else if (low) { main = C.warn; light = C.warnLt; g0 = '#8E6A14'; g1 = C.warn; }
        else { main = '#5CE38A'; light = '#D2FFE4'; g0 = '#1E7A48'; g1 = '#5CE38A'; }
      } else {
        // 燃油沿用既定规则：低于 20% 即转红
        if (low) { main = C.red; light = C.redLt; g0 = '#8E2626'; g1 = C.red; }
        else { main = '#4FC3FF'; light = '#C6ECFF'; g0 = '#175D80'; g1 = '#4FC3FF'; }
      }

      /* 告警描边：低量时整张卡有一圈同色轮廓 */
      if (low) {
        rr(ctx, card.x + 1.5, card.y + 1.5, card.w - 3, card.h - 3, 18.5);
        ctx.strokeStyle = rgba(main, critical ? 0.45 : 0.26);
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }

      /* 1. 色晕：让两张卡各有一种气质 */
      ctx.save();
      rr(ctx, card.x + 1, card.y + 1, card.w - 2, card.h - 2, 19);
      ctx.clip();
      const tint = ctx.createRadialGradient(x + 6, card.y + 4, 0, x + 6, card.y + 4, 230);
      tint.addColorStop(0, rgba(main, low ? 0.13 : 0.09));
      tint.addColorStop(0.55, rgba(main, 0.025));
      tint.addColorStop(1, rgba(main, 0));
      ctx.fillStyle = tint;
      ctx.fillRect(card.x, card.y, card.w, card.h);
      ctx.restore();

      /* 2. 图标胶囊 */
      const ts = 30, tx = x, ty = card.y + 12;
      ctx.save();
      rr(ctx, tx, ty, ts, ts, 9);
      const ig = ctx.createLinearGradient(tx, ty, tx + ts, ty + ts);
      ig.addColorStop(0, rgba(main, 0.18));
      ig.addColorStop(1, rgba(main, 0.035));
      ctx.fillStyle = ig; ctx.fill();
      ctx.strokeStyle = rgba(main, 0.26); ctx.lineWidth = 1; ctx.stroke();

      ctx.translate(tx + ts / 2, ty + ts / 2);
      ctx.strokeStyle = main; ctx.lineWidth = 1.6;
      ctx.lineJoin = 'round'; ctx.lineCap = 'round';
      if (o.kind === 'ev') {
        rr(ctx, -6, -4, 12, 8, 2); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(8, -1); ctx.lineTo(8, 1); ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(1.5, -3); ctx.lineTo(-1.5, 0.5); ctx.lineTo(1.5, 0.5); ctx.lineTo(-1, 3.5);
        ctx.stroke();
      } else {
        ctx.beginPath();
        ctx.moveTo(-6, 6); ctx.lineTo(-6, -5);
        ctx.quadraticCurveTo(-6, -6.5, -4.5, -6.5);
        ctx.lineTo(-2, -6.5);
        ctx.quadraticCurveTo(-0.5, -6.5, -0.5, -5);
        ctx.lineTo(-0.5, 6);
        ctx.stroke();
        ctx.beginPath(); ctx.moveTo(-6, 0); ctx.lineTo(-0.5, 0); ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(-0.5, 1.5); ctx.lineTo(3, 1.5);
        ctx.quadraticCurveTo(5, 1.5, 5, 3.5);
        ctx.lineTo(5, 5.5);
        ctx.stroke();
      }
      ctx.restore();

      /* 3. 名称 + 副信息（原右侧百分比挪下来，信息更连贯） */
      ctx.save();
      ctx.textBaseline = 'alphabetic';
      ctx.fillStyle = low ? main : '#8CA2A7';
      ctx.font = f(11, 400);
      setLS(ctx, 2.2);
      ctx.fillText(o.name, x + 42, card.y + 26);

      ctx.fillStyle = low ? rgba(main, 0.95) : '#4E6165';
      ctx.font = f(9.5, 400);
      setLS(ctx, 1.1);
      ctx.fillText(
        low ? '低于 20% · 请尽快补充' : ('剩余 ' + pct + '% · 满值 ' + o.cap + ' km'),
        x + 42, card.y + 42);
      ctx.restore();

      /* 4. 数值：km 先右对齐，数字贴其左侧 */
      ctx.save();
      ctx.textAlign = 'right';
      ctx.font = f(10.5, 400);
      ctx.fillStyle = C.dim;
      setLS(ctx, 1);
      ctx.fillText('km', right, card.y + 40);
      const uw = ctx.measureText('km').width;

      ctx.font = f(30, 600);
      ctx.fillStyle = light;
      setLS(ctx, -0.5);
      if (low) { ctx.shadowColor = rgba(main, 0.85); ctx.shadowBlur = 14; }
      ctx.fillText(String(o.val), right - uw - 6, card.y + 40);
      ctx.restore();

      /* 5. 进度条 */
      const by = card.y + 56, bh = 9, bw = w;

      // 槽
      ctx.save();
      rr(ctx, x, by, bw, bh, bh / 2);
      ctx.fillStyle = '#0A1114'; ctx.fill();
      ctx.strokeStyle = 'rgba(255,255,255,0.055)'; ctx.lineWidth = 1; ctx.stroke();

      // 槽内顶部阴影，做出凹槽感
      rr(ctx, x, by, bw, bh, bh / 2);
      ctx.clip();
      const sh = ctx.createLinearGradient(0, by, 0, by + bh);
      sh.addColorStop(0, 'rgba(0,0,0,0.55)');
      sh.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = sh;
      ctx.fillRect(x, by, bw, bh * 0.7);
      ctx.restore();

      // 填充
      const fw = bw * ratio * Math.min(1, (o.anim || 1) * 1.15);
      if (fw > 1) {
        ctx.save();
        rr(ctx, x, by, bw, bh, bh / 2);
        ctx.clip();

        const bg = ctx.createLinearGradient(x, 0, x + fw, 0);
        bg.addColorStop(0, g0);
        bg.addColorStop(1, g1);
        ctx.fillStyle = bg;
        ctx.shadowColor = rgba(main, low ? 0.75 : 0.5);
        ctx.shadowBlur = 12;
        ctx.fillRect(x, by, fw, bh);

        // 顶部高光 + 底部压暗：让条子有圆柱感
        ctx.shadowBlur = 0;
        const hl = ctx.createLinearGradient(0, by, 0, by + bh);
        hl.addColorStop(0, 'rgba(255,255,255,0.30)');
        hl.addColorStop(0.45, 'rgba(255,255,255,0.04)');
        hl.addColorStop(1, 'rgba(0,0,0,0.14)');
        ctx.fillStyle = hl;
        ctx.fillRect(x, by, fw, bh);
        ctx.restore();

        // 液面端点光点
        if (fw > 6) {
          const ex = x + fw - bh / 2;
          ctx.save();
          ctx.beginPath();
          ctx.arc(ex, by + bh / 2, 2.6, 0, 6.2832);
          ctx.fillStyle = 'rgba(255,255,255,0.92)';
          ctx.shadowColor = rgba(main, 0.95);
          ctx.shadowBlur = 10;
          ctx.fill();
          ctx.restore();
        }
      }

      /* 6. 20% 阈值刻度 */
      const thrX = x + bw * 0.2;
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(thrX, by + bh + 2);
      ctx.lineTo(thrX, by + bh + 5);
      ctx.strokeStyle = low ? rgba(main, 0.9) : 'rgba(190,210,214,0.22)';
      ctx.lineWidth = 1;
      ctx.stroke();
      ctx.restore();
    }

    /* --------------------------- 灯光状态栏 --------------------------- */
    drawLightRail(ctx, t) {
      const r = L.rail;
      this.panel(ctx, r.x, r.y, r.w, r.h, 20);

      const on = this.data.lights || {};
      const cy = r.y + r.h / 2;
      const blink = Math.floor(t * 1.7) % 2 === 0;   // 转向灯闪烁节拍
      const hz = !!on.hazard;                        // 双闪：左右同相闪烁

      const items = [
        ['left', (hz || on.left) && blink, C.amber],
        ['auto', !!on.auto, C.teal],
        ['low', !!on.low, '#DCEAEA'],
        ['high', !!on.high, '#7FD2FF'],
        ['hold', !!on.hold, C.teal],
        ['fogR', !!on.fogR, C.red],
        ['right', (hz || on.right) && blink, C.amber]
      ];

      const slot = r.w / items.length;
      items.forEach(([kind, active, color], i) => {
        const x = r.x + slot * (i + 0.5);
        ctx.save();
        ctx.translate(x, cy);
        if (active) {
          rr(ctx, -19, -15, 38, 30, 10);
          ctx.fillStyle = rgba(color, 0.10); ctx.fill();
          ctx.strokeStyle = rgba(color, 0.18); ctx.lineWidth = 1; ctx.stroke();
        }
        this.drawLightIcon(ctx, kind, active ? color : '#35484E', active);
        ctx.restore();
      });
    }

    /* 单个灯光图标：以 0,0 为中心，约 24×20 范围 */
    drawLightIcon(ctx, kind, color, active) {
      ctx.save();
      ctx.strokeStyle = color;
      ctx.fillStyle = color;
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      if (active) { ctx.shadowColor = color; ctx.shadowBlur = 10; }

      /* 转向灯：带尾巴的实心箭头，尖端朝左/右 */
      if (kind === 'left' || kind === 'right') {
        const s = kind === 'left' ? -1 : 1;   // -1 指向左，+1 指向右
        ctx.beginPath();
        ctx.moveTo(10 * s, 0);        // 尖端
        ctx.lineTo(-1 * s, -8);
        ctx.lineTo(-1 * s, -3);
        ctx.lineTo(-9 * s, -3);       // 箭尾
        ctx.lineTo(-9 * s, 3);
        ctx.lineTo(-1 * s, 3);
        ctx.lineTo(-1 * s, 8);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
        return;
      }

      /* 自动驻车：(A) 双括号 + A */
      if (kind === 'hold') {
        ctx.beginPath(); ctx.arc(0, 0, 8, 0, 6.2832); ctx.stroke();
        ctx.beginPath(); ctx.arc(0, 0, 11.6, -0.44, 0.44); ctx.stroke();
        ctx.beginPath(); ctx.arc(0, 0, 11.6, Math.PI - 0.44, Math.PI + 0.44); ctx.stroke();
        ctx.font = f(9.5, 700);
        setLS(ctx, 0);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('A', 0, 0.5);
        ctx.restore();
        return;
      }

      /* 灯体 */
      ctx.beginPath();
      ctx.ellipse(-1, 0, 5.5, 8.5, -0.3, 0, 6.2832);
      ctx.stroke();

      if (kind === 'auto') {
        // 灯体内的 A
        ctx.font = f(8.5, 700);
        setLS(ctx, 0);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('A', -1, 0.5);
        // 短光束
        ctx.beginPath();
        ctx.moveTo(8, -5); ctx.lineTo(12, -5);
        ctx.moveTo(9, 0); ctx.lineTo(13, 0);
        ctx.moveTo(8, 5); ctx.lineTo(12, 5);
        ctx.stroke();
      } else if (kind === 'high') {
        // 远光：平直长光束
        ctx.beginPath();
        ctx.moveTo(8, -6); ctx.lineTo(15, -6);
        ctx.moveTo(9, 0); ctx.lineTo(16, 0);
        ctx.moveTo(8, 6); ctx.lineTo(15, 6);
        ctx.stroke();
      } else if (kind === 'low') {
        // 近光：向下倾斜
        ctx.beginPath();
        ctx.moveTo(7, -5); ctx.lineTo(13, 1);
        ctx.moveTo(8, 0.5); ctx.lineTo(14, 6);
        ctx.moveTo(7, 5); ctx.lineTo(13, 11);
        ctx.stroke();
      } else {
        // 雾灯：斜光束 + 雾线
        ctx.beginPath();
        ctx.moveTo(7, -5); ctx.lineTo(12, 0);
        ctx.moveTo(8, 1); ctx.lineTo(13, 6);
        ctx.stroke();

        ctx.beginPath();
        ctx.lineWidth = 1.8;
        if (kind === 'fogR') {
          ctx.moveTo(-6, 11); ctx.lineTo(10, 11);
        } else {
          ctx.moveTo(-6, 11);
          ctx.bezierCurveTo(-2, 8, 2, 14, 6, 11);
          ctx.lineTo(10, 11);
        }
        ctx.stroke();
      }
      ctx.restore();
    }

    /* --------------------------- 地图 --------------------------- */
    drawMap(ctx, t) {
      const m = L.map;
      ctx.save();
      rr(ctx, m.x, m.y, m.w, m.h, 20);
      ctx.clip();

      // 底色（clip 之后是绝对坐标，必须落在卡片区域内）
      let rg = ctx.createRadialGradient(
        m.x + m.w * 0.5, m.y + m.h * 0.46, 40,
        m.x + m.w * 0.5, m.y + m.h * 0.46, m.w * 0.72);
      rg.addColorStop(0, '#0F1B21'); rg.addColorStop(1, '#060B0E');
      ctx.fillStyle = rg; ctx.fillRect(m.x, m.y, m.w, m.h);

      ctx.save();
      // 路网以 1040×520 虚拟坐标绘制，cover 缩放居中：不拉伸、不压扁
      const VW = 1040, VH = 520;
      const ms = Math.max(m.w / VW, m.h / VH);
      ctx.translate(m.x + (m.w - VW * ms) / 2, m.y + (m.h - VH * ms) / 2);
      ctx.scale(ms, ms);

      // 水系
      ctx.beginPath();
      ctx.moveTo(-40, -40); ctx.lineTo(300, -40);
      ctx.bezierCurveTo(276, 26, 214, 62, 146, 96);
      ctx.bezierCurveTo(86, 126, 22, 138, -40, 120);
      ctx.closePath();
      ctx.fillStyle = '#0A1721'; ctx.fill();
      ctx.strokeStyle = '#122A38'; ctx.lineWidth = 1.5; ctx.stroke();

      // 绿地
      rr(ctx, 612, 316, 186, 126, 16);
      ctx.fillStyle = '#0B1E18'; ctx.fill();
      ctx.strokeStyle = '#14352A'; ctx.lineWidth = 1; ctx.stroke();
      const trees = [[650, 352, 5], [672, 360, 4], [700, 346, 6], [736, 368, 5],
                     [762, 352, 4], [690, 398, 5], [742, 404, 6], [770, 400, 4]];
      ctx.fillStyle = '#16402F';
      for (const [tx, ty, tr] of trees) { ctx.beginPath(); ctx.arc(tx, ty, tr, 0, 6.2832); ctx.fill(); }

      // 街区
      ctx.fillStyle = '#0C1518';
      for (const b of BLOCKS) { rr(ctx, b[0], b[1], b[2], b[3], 3); ctx.fill(); }

      // 支路
      ctx.save();
      ctx.translate(520, 260); ctx.rotate(-2 * Math.PI / 180); ctx.translate(-520, -260);
      ctx.strokeStyle = '#122026'; ctx.lineWidth = 7; ctx.lineCap = 'round';
      for (const x of MINOR_V) { ctx.beginPath(); ctx.moveTo(x, -30); ctx.lineTo(x, 560); ctx.stroke(); }
      for (const y of MINOR_H) { ctx.beginPath(); ctx.moveTo(-30, y); ctx.lineTo(1080, y); ctx.stroke(); }
      ctx.restore();

      // 主干道
      ctx.strokeStyle = '#1D323A'; ctx.lineWidth = 13;
      for (const x of MAJOR_V) { ctx.beginPath(); ctx.moveTo(x, -30); ctx.lineTo(x, 560); ctx.stroke(); }
      for (const y of MAJOR_H) { ctx.beginPath(); ctx.moveTo(-30, y); ctx.lineTo(1080, y); ctx.stroke(); }

      // 高架
      tracePoly(ctx, HIGHWAY);
      ctx.strokeStyle = '#243B44'; ctx.lineWidth = 26;
      ctx.lineJoin = 'round'; ctx.stroke();
      ctx.save();
      ctx.setLineDash([14, 16]);
      ctx.strokeStyle = 'rgba(62,97,107,0.55)'; ctx.lineWidth = 2;
      ctx.stroke();
      ctx.restore();

      // 路名
      ctx.save();
      ctx.font = f(11.5, 400);
      setLS(ctx, 1.6);
      for (const [lx, ly, txt, col, rot] of MAP_LABELS) {
        ctx.save();
        ctx.translate(lx, ly);
        ctx.rotate(rot * Math.PI / 180);
        ctx.fillStyle = col;
        ctx.fillText(txt, 0, 0);
        ctx.restore();
      }
      ctx.restore();

      if (this.data.navActive !== false) {
      /* 车道：三车道，本车道高亮 */
      const LW = 18;
      const laneB = [-1.5 * LW, -0.5 * LW, 0.5 * LW, 1.5 * LW].map(d => offsetPoly(ROUTE.pts, d));

      const bandPath = (a, b) => {
        ctx.beginPath();
        ctx.moveTo(a[0][0], a[0][1]);
        for (let i = 1; i < a.length; i++) ctx.lineTo(a[i][0], a[i][1]);
        for (let i = b.length - 1; i >= 0; i--) ctx.lineTo(b[i][0], b[i][1]);
        ctx.closePath();
      };

      // 路面
      bandPath(laneB[0], laneB[3]);
      ctx.fillStyle = 'rgba(23,38,45,0.60)';
      ctx.fill();

      // 本车道
      bandPath(laneB[1], laneB[2]);
      ctx.fillStyle = 'rgba(67,232,200,0.055)';
      ctx.fill();

      ctx.save();
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';

      // 路缘
      for (const i of [0, 3]) {
        tracePts(ctx, laneB[i]);
        ctx.strokeStyle = 'rgba(180,215,220,0.13)';
        ctx.lineWidth = 1.4;
        ctx.stroke();
      }

      // 车道分隔虚线
      ctx.setLineDash([9, 11]);
      for (const i of [1, 2]) {
        tracePts(ctx, laneB[i]);
        ctx.strokeStyle = 'rgba(226,245,246,0.26)';
        ctx.lineWidth = 1.4;
        ctx.stroke();
      }
      ctx.setLineDash([]);
      ctx.restore();

      // 路线
      ctx.save();
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      tracePoly(ctx, ROUTE);
      ctx.strokeStyle = 'rgba(67,232,200,0.10)';
      ctx.lineWidth = 14; ctx.stroke();

      const rgd = ctx.createLinearGradient(262, 470, 842, 128);
      rgd.addColorStop(0, '#2FBFA8'); rgd.addColorStop(1, '#7BFFE6');
      tracePoly(ctx, ROUTE);
      ctx.strokeStyle = rgd; ctx.lineWidth = 6; ctx.stroke();

      ctx.setLineDash([10, 12]);
      ctx.lineDashOffset = -(t * 20) % 22;
      ctx.strokeStyle = 'rgba(201,255,246,0.85)';
      ctx.lineWidth = 1.6; ctx.stroke();
      ctx.restore();
      }

      // 车流
      for (const tf of TRAFFIC) {
        const u = (tf.u + (t / tf.dur)) % 1;
        const p = polyAt(tf.m, tf.m === HIGHWAY ? u : ((u <= 0.5) ? u * 2 : (1 - u) * 2));
        ctx.beginPath();
        ctx.arc(p.x, p.y, 3.2, 0, 6.2832);
        ctx.fillStyle = tf.c;
        ctx.globalAlpha = 0.72; ctx.fill();
        ctx.globalAlpha = 1;
      }

      // 终点
      if (this.data.navActive !== false) {
      ctx.save();
      ctx.beginPath(); ctx.arc(842, 128, 14, 0, 6.2832);
      ctx.fillStyle = 'rgba(67,232,200,0.14)'; ctx.fill();
      ctx.beginPath(); ctx.arc(842, 128, 6, 0, 6.2832);
      ctx.fillStyle = C.teal;
      ctx.shadowColor = 'rgba(67,232,200,0.8)'; ctx.shadowBlur = 14;
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.font = f(11, 400); setLS(ctx, 1.4);
      ctx.fillStyle = '#9FE3D6'; ctx.fillText('目的地', 862, 124);
      ctx.font = f(10, 400);
      ctx.fillStyle = '#57726F'; ctx.fillText('09:52 到达', 862, 140);
      ctx.restore();
      }

      // 自车
      const cp = polyAt(ROUTE, 0.168);
      const pulse = (t * 0.42) % 1;
      ctx.save();
      ctx.beginPath();
      ctx.arc(cp.x, cp.y, 8 + pulse * 14, 0, 6.2832);
      ctx.strokeStyle = `rgba(123,255,230,${0.5 * (1 - pulse)})`;
      ctx.lineWidth = 2; ctx.stroke();
      ctx.restore();

      ctx.save();
      ctx.translate(cp.x, cp.y);
      ctx.beginPath(); ctx.arc(0, 0, 9, 0, 6.2832);
      ctx.fillStyle = 'rgba(9,18,20,0.9)'; ctx.fill();
      ctx.strokeStyle = '#7BFFE6'; ctx.lineWidth = 1.4; ctx.stroke();
      ctx.rotate(cp.ang + Math.PI / 2);
      ctx.beginPath();
      ctx.moveTo(0, -6.5); ctx.lineTo(4.2, 5); ctx.lineTo(0, 3); ctx.lineTo(-4.2, 5);
      ctx.closePath();
      ctx.fillStyle = '#CFFEF6'; ctx.fill();
      ctx.restore();

      ctx.restore(); // 回到画布坐标

      // 暗角
      rg = ctx.createRadialGradient(m.x + m.w / 2, m.y + m.h / 2, m.h * 0.42,
                                    m.x + m.w / 2, m.y + m.h / 2, m.w * 0.62);
      rg.addColorStop(0, 'rgba(3,7,9,0)');
      rg.addColorStop(1, 'rgba(3,7,9,0.82)');
      ctx.fillStyle = rg; ctx.fillRect(m.x, m.y, m.w, m.h);
      ctx.restore();

      rr(ctx, m.x + 0.5, m.y + 0.5, m.w - 1, m.h - 1, 20);
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      // 浮层
      this.chip(ctx, m.x + 14, m.y + 14, 0, () => {
        ctx.beginPath(); ctx.arc(0, 0, 3, 0, 6.2832);
        const pulse = (t * 0.45) % 1;
        ctx.globalAlpha = 1 - pulse * 0.6;
        ctx.fillStyle = C.teal;
        ctx.shadowColor = C.teal; ctx.shadowBlur = 8;
        ctx.fill(); ctx.shadowBlur = 0; ctx.globalAlpha = 1;
      }, '实时路况 · 畅通', 122);

      this.chip(ctx, m.x + m.w - 14 - 118, m.y + 14, 0, () => {
        ctx.save();
        ctx.strokeStyle = '#8FB6BC'; ctx.lineWidth = 1.8; ctx.lineJoin = 'round';
        ctx.beginPath();
        ctx.moveTo(0, -6); ctx.lineTo(-6, 6); ctx.lineTo(0, 2.5); ctx.lineTo(6, 6);
        ctx.closePath(); ctx.stroke();
        ctx.restore();
      }, 'N · 北偏东 12°', 118, 8);

      this.chip(ctx, m.x + 14, m.y + m.h - 14 - 28, 0, () => {
        ctx.save();
        ctx.strokeStyle = '#9FB4B8'; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.moveTo(-4, 0); ctx.lineTo(4, 0);
        ctx.moveTo(0, -4); ctx.lineTo(0, 4); ctx.stroke();
        ctx.restore();
      }, '', 64, 0, true);

      ctx.save();
      const sw = 62, sx = m.x + m.w - 14 - sw, sy = m.y + m.h - 14 - 20;
      rr(ctx, sx, sy, sw, 20, 10);
      ctx.fillStyle = 'rgba(8,14,17,0.78)'; ctx.fill();
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
      ctx.font = f(10.5, 400); setLS(ctx, 1.6);
      ctx.fillStyle = '#7C9095';
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText('200 m', sx + sw / 2, sy + 10.5);
      ctx.restore();

      // 车道引导条与是否导航无关：有 lanes 数据就画，空数组自动隐藏
      this.drawLaneGuide(ctx);
    }

    /* --------------------------- 车道引导条 --------------------------- */
    drawLaneGuide(ctx) {
      const m = L.map;
      /* 归一化：支持字符串数组 + laneActive，或 [{dir, active}] 对象数组 */
      const DIR = v => {
        const k = String(v == null ? '' : v).toLowerCase();
        if (k === 'left' || k === 'l' || k === '←' || k === '左' || k === '左转') return 'left';
        if (k === 'right' || k === 'r' || k === '→' || k === '↱' || k === '右' || k === '右转') return 'right';
        return 'straight';
      };
      const actIdx = this.data.laneActive || [];
      const lanes = (this.data.lanes || []).map((l, i) => (
        l && typeof l === 'object'
          ? { dir: DIR(l.dir), on: !!l.active }
          : { dir: DIR(l), on: actIdx.indexOf(i) >= 0 }
      ));
      if (!lanes.length) return;

      const cell = 50, pad = 12, h = 46;
      const w = lanes.length * cell + pad * 2;
      const x = m.x + (m.w - w) / 2;
      const y = m.y + 14;

      ctx.save();
      rr(ctx, x, y, w, h, 12);
      ctx.fillStyle = 'rgba(7,12,15,0.86)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(120,170,180,0.20)';
      ctx.lineWidth = 1;
      ctx.stroke();

      lanes.forEach((lane, i) => {
        const cx = x + pad + cell * (i + 0.5);
        const cy = y + h / 2;
        const dir = lane.dir, on = lane.on;

        if (on) {
          const g = ctx.createRadialGradient(cx, cy + 8, 2, cx, cy + 8, 26);
          g.addColorStop(0, rgba(C.teal, 0.20));
          g.addColorStop(1, rgba(C.teal, 0));
          ctx.fillStyle = g;
          ctx.fillRect(cx - 26, cy - 16, 52, 34);
        }

        this.laneArrow(ctx, cx, cy, dir, on ? C.teal : 'rgba(226,241,242,0.50)', on);

        if (i < lanes.length - 1) {
          const dx = x + pad + cell * (i + 1);
          ctx.beginPath();
          ctx.moveTo(dx, cy - 9);
          ctx.lineTo(dx, cy + 9);
          ctx.strokeStyle = 'rgba(120,170,180,0.22)';
          ctx.lineWidth = 1;
          ctx.stroke();
        }
      });
      ctx.restore();
    }

    /* 单个车道箭头 */
    laneArrow(ctx, cx, cy, dir, color, glow) {
      ctx.save();
      ctx.translate(cx, cy);
      if (dir === 'left') ctx.scale(-1, 1);
      ctx.fillStyle = color;
      ctx.strokeStyle = color;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      if (glow) {
        ctx.shadowColor = rgba(C.teal, 0.85);
        ctx.shadowBlur = 12;
      }

      if (dir === 'straight') {
        ctx.beginPath();
        ctx.moveTo(0, -11);
        ctx.lineTo(6.5, -3);
        ctx.lineTo(2.7, -3);
        ctx.lineTo(2.7, 11);
        ctx.lineTo(-2.7, 11);
        ctx.lineTo(-2.7, -3);
        ctx.lineTo(-6.5, -3);
        ctx.closePath();
        ctx.fill();
      } else {
        ctx.lineWidth = 3.4;
        ctx.beginPath();
        ctx.moveTo(-1, 11);
        ctx.lineTo(-1, -2);
        ctx.quadraticCurveTo(-1, -7.5, 4.5, -7.5);
        ctx.lineTo(6.5, -7.5);
        ctx.stroke();

        ctx.beginPath();
        ctx.moveTo(6, -13);
        ctx.lineTo(13, -7.5);
        ctx.lineTo(6, -2);
        ctx.closePath();
        ctx.fill();
      }
      ctx.restore();
    }

    chip(ctx, x, y, w, drawIcon, label, width, iconGap, isZoom) {
      const h = 28;
      ctx.save();
      if (isZoom) {
        rr(ctx, x, y, 60, h, 14);
        ctx.fillStyle = 'rgba(8,14,17,0.78)'; ctx.fill();
        ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
        ctx.beginPath(); ctx.moveTo(x + 30, y + 5); ctx.lineTo(x + 30, y + h - 5);
        ctx.strokeStyle = C.line; ctx.stroke();
        ctx.translate(x + 15, y + h / 2); drawIcon();
        ctx.translate(30, 0);
        ctx.strokeStyle = '#9FB4B8'; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.moveTo(-4, 0); ctx.lineTo(4, 0); ctx.stroke();
        ctx.restore();
        return;
      }
      const tw = width || 120;
      rr(ctx, x, y, tw, h, 14);
      ctx.fillStyle = 'rgba(8,14,17,0.78)'; ctx.fill();
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      ctx.translate(x + 15, y + h / 2);
      if (drawIcon) drawIcon();
      ctx.restore();

      if (label) {
        ctx.save();
        ctx.font = f(11.5, 400);
        setLS(ctx, 1.4);
        ctx.fillStyle = '#A9BEC2';
        ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
        ctx.fillText(label, x + 26, y + h / 2 + 0.5);
        ctx.restore();
      }
      ctx.restore();
    }

    /* --------------------------- 时钟卡 --------------------------- */
    drawClock(ctx) {
      const c = L.clock;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      const d = new Date();
      const hh = String(d.getHours()).padStart(2, '0');
      const mm = String(d.getMinutes()).padStart(2, '0');

      ctx.save();
      ctx.fillStyle = '#F2FAFA';
      ctx.font = f(44, 300);
      setLS(ctx, 2);
      ctx.textBaseline = 'alphabetic';
      ctx.fillText(hh + ':' + mm, c.x + 20, c.y + 54);

      const wd = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
      ctx.fillStyle = C.dim;
      ctx.font = f(11.5, 400);
      setLS(ctx, 2);
      ctx.fillText(wd + ' · ' + (d.getMonth() + 1) + '月' + d.getDate() + '日',
                   c.x + 22, c.y + 76);
      ctx.restore();

      ctx.save();
      ctx.textAlign = 'right';
      ctx.fillStyle = '#DCEBEB';
      ctx.font = f(28, 400);
      setLS(ctx, 1);
      ctx.fillText(this.data.outdoor, c.x + c.w - 20, c.y + 46);

      ctx.fillStyle = C.dim;
      ctx.font = f(11, 400);
      setLS(ctx, 1.6);
      ctx.fillText(this.data.weather, c.x + c.w - 20, c.y + 66);
      ctx.fillStyle = C.dim3;
      ctx.font = f(10.5, 400);
      setLS(ctx, 1.2);
      ctx.fillText('车外 ' + this.data.outdoor + ' / 车内 ' + this.data.indoor,
                   c.x + c.w - 20, c.y + 82);
      ctx.restore();
    }

    /* --------------------------- 导航 / 行程横条（地图下方） --------------------------- */
    drawNavStrip(ctx) {
      const r = L.navStrip;
      this.panel(ctx, r.x, r.y, r.w, r.h);

      const cyC = r.y + r.h / 2;
      const navOn = this.data.navActive !== false;
      // 导航结束后按结束原因分流：到达目的地 → 本次行程；主动退出 → 胎压
      const endedByArrival = this.data.navEnd === 'arrived';

      if (!navOn && !endedByArrival) { this.drawTireStrip(ctx, r); return; }

      /* 左侧图标 */
      const ts = 34, tx0 = r.x + 18, ty0 = cyC - ts / 2;
      rr(ctx, tx0, ty0, ts, ts, 10);
      const bg = ctx.createLinearGradient(tx0, ty0, tx0 + ts, ty0 + ts);
      bg.addColorStop(0, 'rgba(67,232,200,0.18)');
      bg.addColorStop(1, 'rgba(67,232,200,0.04)');
      ctx.fillStyle = bg; ctx.fill();
      ctx.strokeStyle = 'rgba(67,232,200,0.28)';
      ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      ctx.translate(tx0 + ts / 2, ty0 + ts / 2);
      ctx.strokeStyle = C.teal;
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      if (navOn) {
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(-4, 5); ctx.lineTo(-4, -1);
        ctx.quadraticCurveTo(-4, -4, -1, -4);
        ctx.lineTo(5, -4);
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(2, -7); ctx.lineTo(6, -4); ctx.lineTo(2, -1);
        ctx.stroke();
      } else {
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.moveTo(-7, 8); ctx.lineTo(-3, -8);
        ctx.moveTo(7, 8); ctx.lineTo(3, -8);
        ctx.stroke();
        ctx.setLineDash([3, 3.5]);
        ctx.beginPath();
        ctx.moveTo(0, 8); ctx.lineTo(0, -8);
        ctx.stroke();
      }
      ctx.restore();

      /* 主文字 */
      const textX = tx0 + ts + 14;
      ctx.save();
      ctx.textAlign = 'left';
      if (navOn) {
        ctx.fillStyle = '#F0FBFA';
        ctx.font = f(22, 600);
        setLS(ctx, 0.5);
        ctx.fillText(this.data.navDist, textX, cyC - 2);
        const dw = ctx.measureText(this.data.navDist).width;

        ctx.fillStyle = C.dim;
        ctx.font = f(11, 400);
        setLS(ctx, 1);
        ctx.fillText(this.data.navUnit, textX + dw + 5, cyC - 2);

        ctx.fillStyle = '#93A9AE';
        ctx.font = f(11.5, 400);
        setLS(ctx, 1.2);
        ctx.fillText(this.data.navTurn + '转进入 ', textX, cyC + 16);
        const tw2 = ctx.measureText(this.data.navTurn + '转进入 ').width;
        ctx.fillStyle = '#D8E9E9';
        ctx.font = f(11.5, 500);
        ctx.fillText(this.data.navStreet, textX + tw2 + 2, cyC + 16);
      } else {
        ctx.fillStyle = '#F0FBFA';
        ctx.font = f(16, 500);
        setLS(ctx, 1);
        ctx.fillText('本次行程', textX, cyC - 2);

        ctx.fillStyle = C.dim;
        ctx.font = f(11, 400);
        setLS(ctx, 1.2);
        ctx.fillText('平均车速 ' + this.data.tripAvg + ' km/h', textX, cyC + 16);
      }
      ctx.restore();

      /* 竖分隔 */
      ctx.beginPath();
      ctx.moveTo(r.x + 320, r.y + 14);
      ctx.lineTo(r.x + 320, r.y + r.h - 14);
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      /* 信息列：导航 3 列 / 行程 4 列，按列数等分 */
      const cols = navOn
        ? [['剩余里程', this.data.navLeft, ''],
           ['预计到达', this.data.navEta, ''],
           ['剩余时间', this.data.navTime, '']]
        : [['行驶里程', this.data.tripKm, 'km'],
           ['平均电耗', this.data.tripEnergy, 'kWh/100km'],
           ['平均油耗', this.data.tripFuel, 'L/100km'],
           ['行驶时间', this.data.tripMin, '分钟']];

      const cx0 = r.x + 330, cx1 = r.x + r.w - 22;
      const step = (cx1 - cx0) / cols.length;

      cols.forEach(([k, v, u], i) => {
        const ccx = cx0 + step * (i + 0.5);

        ctx.save();
        ctx.textAlign = 'center';
        ctx.fillStyle = C.dim;
        ctx.font = f(10, 400);
        setLS(ctx, 1.8);
        ctx.fillText(k, ccx, r.y + 26);

        ctx.font = f(15, 500);
        setLS(ctx, 0.6);
        const vw = ctx.measureText(v).width;
        ctx.font = f(9.5, 400);
        setLS(ctx, 0.8);
        const uw = u ? ctx.measureText(u).width + 4 : 0;
        const startX = ccx - (vw + uw) / 2;

        ctx.textAlign = 'left';
        ctx.font = f(15, 500);
        ctx.fillStyle = '#DCEAEA';
        ctx.fillText(v, startX, r.y + 47);

        if (u) {
          ctx.font = f(9.5, 400);
          ctx.fillStyle = C.dim;
          ctx.fillText(u, startX + vw + 4, r.y + 47);
        }
        ctx.restore();

        if (i < cols.length - 1) {
          const dx = cx0 + step * (i + 1);
          ctx.beginPath();
          ctx.moveTo(dx, r.y + 16);
          ctx.lineTo(dx, r.y + 48);
          ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
        }
            });
    }

    /* 非导航态 B：胎压监测 */
    drawTireStrip(ctx, r) {
      const cyC = r.y + r.h / 2;
      const ts = 34, tx0 = r.x + 18, ty0 = cyC - ts / 2;

      rr(ctx, tx0, ty0, ts, ts, 10);
      const bg = ctx.createLinearGradient(tx0, ty0, tx0 + ts, ty0 + ts);
      bg.addColorStop(0, 'rgba(67,232,200,0.18)');
      bg.addColorStop(1, 'rgba(67,232,200,0.04)');
      ctx.fillStyle = bg; ctx.fill();
      ctx.strokeStyle = 'rgba(67,232,200,0.28)'; ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      ctx.translate(tx0 + ts / 2, ty0 + ts / 2);
      ctx.strokeStyle = C.teal;
      ctx.lineWidth = 1.6;
      rr(ctx, -5, -9, 10, 18, 4);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(-3, -3); ctx.lineTo(3, -3);
      ctx.moveTo(-3, 3); ctx.lineTo(3, 3);
      ctx.stroke();
      ctx.restore();

      const textX = tx0 + ts + 14;
      const tires = this.data.tires || [];
      const low = tires.some(t => t[1] < 2.2);

      ctx.save();
      ctx.textAlign = 'left';
      ctx.fillStyle = '#F0FBFA';
      ctx.font = f(15, 500);
      setLS(ctx, 1);
      ctx.fillText('胎压监测', textX, cyC - 3);

      ctx.fillStyle = low ? C.warn : C.teal;
      ctx.font = f(11, 400);
      setLS(ctx, 1.2);
      ctx.fillText(low ? '右后偏低 · 建议检查' : '四轮正常', textX, cyC + 15);
      ctx.restore();

      tires.forEach(([name, v], i) => {
        const cx = r.x + 300 + i * 140;
        const warn = v < 2.2;

        ctx.save();
        ctx.textAlign = 'left';
        ctx.fillStyle = C.dim;
        ctx.font = f(10, 400);
        setLS(ctx, 1.8);
        ctx.fillText(name, cx, r.y + 22);

        ctx.fillStyle = warn ? C.warn : '#DCEAEA';
        ctx.font = f(16, 500);
        setLS(ctx, 0.6);
        if (warn) { ctx.shadowColor = rgba(C.warn, 0.7); ctx.shadowBlur = 8; }
        ctx.fillText(v.toFixed(1), cx, r.y + 44);
        const vw = ctx.measureText(v.toFixed(1)).width;
        ctx.fillStyle = C.dim;
        ctx.font = f(9.5, 400);
        ctx.shadowBlur = 0;
        ctx.fillText('bar', cx + vw + 4, r.y + 44);
        ctx.restore();

        /* 压力刻度：2.0 → 3.0，正常区 2.2–2.6 */
        const gw = 100, gy = r.y + 53;
        const T = p => cx + gw * clamp((p - 2.0) / 1.0, 0, 1);

        ctx.save();
        rr(ctx, cx, gy, gw, 3, 1.5);
        ctx.fillStyle = 'rgba(255,255,255,0.07)'; ctx.fill();

        rr(ctx, T(2.2), gy, Math.max(1, T(2.6) - T(2.2)), 3, 1.5);
        ctx.fillStyle = 'rgba(67,232,200,0.20)'; ctx.fill();

        ctx.fillStyle = warn ? C.warn : C.teal;
        if (warn) { ctx.shadowColor = rgba(C.warn, 0.8); ctx.shadowBlur = 8; }
        rr(ctx, T(v) - 1, gy - 3, 2, 9, 1);
        ctx.fill();
        ctx.restore();
      });
    }

    /* --------------------------- 媒体待机态 --------------------------- */
    drawMediaIdle(ctx) {
      const c = L.media;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      const cs = 132, cx0 = c.x + 22, cy0 = c.y + 26;
      const ccx = cx0 + cs / 2, ccy = cy0 + cs / 2, cr = cs / 2;

      /* 唱片压暗、静止，不画进度只作留白 */
      ctx.save();
      ctx.globalAlpha = 0.5;
      ctx.beginPath(); ctx.arc(ccx, ccy, cr, 0, 6.2832);
      ctx.fillStyle = '#0A1215'; ctx.fill();

      const cg = ctx.createLinearGradient(cx0, cy0, cx0 + cs, cy0 + cs);
      cg.addColorStop(0, '#0B1B22');
      cg.addColorStop(0.55, '#0E2129');
      cg.addColorStop(1, '#171430');
      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 9, 0, 6.2832);
      ctx.fillStyle = cg; ctx.fill();

      for (let i = 0; i < 4; i++) {
        ctx.beginPath();
        ctx.arc(ccx, ccy, cr - 7 + i * 3, 0, 6.2832);
        ctx.strokeStyle = 'rgba(255,255,255,0.03)';
        ctx.lineWidth = 1; ctx.stroke();
      }

      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 0.5, 0, 6.2832);
      ctx.strokeStyle = 'rgba(120,200,190,0.16)';
      ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();

      /* 转轴 */
      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, 4.5, 0, 6.2832);
      ctx.fillStyle = 'rgba(6,12,15,0.9)'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,200,190,0.18)';
      ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();

      /* 文案 */
      const tx = cx0 + cs + 18;
      ctx.save();
      ctx.fillStyle = '#DCEAEA';
      ctx.font = f(15, 500);
      setLS(ctx, 0.8);
      ctx.fillText('未在播放', tx, c.y + 58);

      /* 空闲状态点 */
      ctx.beginPath();
      ctx.arc(tx + 3, c.y + 80, 2.4, 0, 6.2832);
      ctx.fillStyle = '#3D5054';
      ctx.fill();

      ctx.fillStyle = '#3D5054';
      ctx.font = f(9.5, 400);
      setLS(ctx, 1.6);
      ctx.fillText('等待媒体信号', tx + 12, c.y + 84);
      ctx.restore();

      /* 静止频谱 */
      const eqX = c.x + 22, eqW = c.w - 44, eqY = c.y + 236, eqH = 18;
      const bars = 42, bw = 5, gapB = (eqW - bars * bw) / (bars - 1);
      ctx.save();
      for (let i = 0; i < bars; i++) {
        const hh = 4 + 5 * Math.abs(Math.sin(i * 0.7));
        rr(ctx, eqX + i * (bw + gapB), eqY + eqH - hh, bw, hh, 2);
        ctx.fillStyle = 'rgba(67,232,200,0.07)';
        ctx.fill();
      }
      ctx.restore();

      /* 空进度 */
      const px = c.x + 22, pw = c.w - 44, py = c.y + 288;
      rr(ctx, px, py, pw, 3, 1.5);
      ctx.fillStyle = 'rgba(255,255,255,0.06)'; ctx.fill();

      ctx.save();
      ctx.font = f(9, 400);
      setLS(ctx, 0.6);
      ctx.fillStyle = C.dim3;
      ctx.fillText('0:00', px, c.y + 306);
      ctx.textAlign = 'right';
      ctx.fillText('--:--', px + pw, c.y + 306);
      ctx.restore();
    }

    /* --------------------------- 媒体卡：封面 + 歌词 --------------------------- */
    drawMedia(ctx, t) {
      if (this.data.mediaPlaying === false) return this.drawMediaIdle(ctx);
      const c = L.media;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      /* ---------- 圆形唱片 ---------- */
      const cs = 132, cx0 = c.x + 22, cy0 = c.y + 26;
      const ccx = cx0 + cs / 2, ccy = cy0 + cs / 2, cr = cs / 2;
      const artR = cr - 9;

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, cr, 0, 6.2832);
      ctx.fillStyle = '#0A1215'; ctx.fill();
      for (let i = 0; i < 4; i++) {
        ctx.beginPath();
        ctx.arc(ccx, ccy, artR + 2 + i * 3, 0, 6.2832);
        ctx.strokeStyle = 'rgba(255,255,255,0.035)';
        ctx.lineWidth = 1; ctx.stroke();
      }
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, artR, 0, 6.2832); ctx.clip();
      ctx.translate(ccx, ccy);
      ctx.rotate(t * 0.45);
      ctx.translate(-ccx, -ccy);

      const cg = ctx.createLinearGradient(cx0, cy0, cx0 + cs, cy0 + cs);
      cg.addColorStop(0, '#0D2C36');
      cg.addColorStop(0.5, '#123A46');
      cg.addColorStop(1, '#241A3E');
      ctx.fillStyle = cg;
      ctx.fillRect(cx0 - 4, cy0 - 4, cs + 8, cs + 8);

      const stars = [[0.18, 0.20, 1.2], [0.30, 0.34, 0.9], [0.12, 0.44, 1.0],
                     [0.46, 0.16, 0.8], [0.86, 0.52, 1.1], [0.72, 0.70, 0.8],
                     [0.56, 0.88, 0.9], [0.22, 0.72, 0.7]];
      ctx.fillStyle = 'rgba(200,255,246,0.55)';
      for (const [sx, sy, sr] of stars) {
        ctx.beginPath();
        ctx.arc(cx0 + cs * sx, cy0 + cs * sy, sr, 0, 6.2832);
        ctx.fill();
      }

      const mx = cx0 + cs * 0.66, my = cy0 + cs * 0.30;
      ctx.beginPath(); ctx.arc(mx, my, cs * 0.23, 0, 6.2832);
      ctx.fillStyle = 'rgba(123,255,230,0.10)'; ctx.fill();
      ctx.beginPath(); ctx.arc(mx, my, cs * 0.15, 0, 6.2832);
      ctx.fillStyle = 'rgba(182,255,242,0.9)';
      ctx.shadowColor = 'rgba(67,232,200,0.9)';
      ctx.shadowBlur = 18;
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.beginPath();
      ctx.moveTo(cx0 - 16, cy0 + cs * 0.70);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.40);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.56);
      ctx.lineTo(cx0 - 16, cy0 + cs * 0.86);
      ctx.closePath();
      ctx.fillStyle = 'rgba(123,255,230,0.10)'; ctx.fill();

      ctx.beginPath();
      ctx.moveTo(cx0 - 16, cy0 + cs * 0.88);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.62);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.71);
      ctx.lineTo(cx0 - 16, cy0 + cs);
      ctx.closePath();
      ctx.fillStyle = 'rgba(255,255,255,0.06)'; ctx.fill();

      const vg = ctx.createRadialGradient(ccx, ccy, artR * 0.45, ccx, ccy, artR);
      vg.addColorStop(0, 'rgba(4,8,10,0)');
      vg.addColorStop(1, 'rgba(4,8,10,0.55)');
      ctx.fillStyle = vg;
      ctx.fillRect(cx0 - 4, cy0 - 4, cs + 8, cs + 8);
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, 4.5, 0, 6.2832);
      ctx.fillStyle = '#05090B'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,200,190,0.35)';
      ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 0.5, 0, 6.2832);
      ctx.strokeStyle = 'rgba(120,200,190,0.28)';
      ctx.lineWidth = 1;
      ctx.shadowColor = 'rgba(67,232,200,0.35)';
      ctx.shadowBlur = 10;
      ctx.stroke();
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 1, 0, 6.2832);
      ctx.clip();
      const sg = ctx.createLinearGradient(cx0, cy0, cx0 + cs * 0.72, cy0 + cs);
      sg.addColorStop(0, 'rgba(255,255,255,0.10)');
      sg.addColorStop(0.45, 'rgba(255,255,255,0.02)');
      sg.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = sg;
      ctx.fillRect(cx0, cy0, cs, cs);
      ctx.restore();

      /* ---------- 歌名 ---------- */
      const tx = cx0 + cs + 18;
      ctx.save();
      ctx.fillStyle = '#DCEAEA';
      ctx.font = f(15, 500);
      setLS(ctx, 0.8);
      ctx.fillText(this.data.mediaTitle, tx, c.y + 58);
      const titleW = ctx.measureText(this.data.mediaTitle).width;

      ctx.fillStyle = C.dim2;
      ctx.font = f(10.5, 400);
      setLS(ctx, 1.2);
      ctx.fillText('· ' + this.data.mediaArtist, tx + titleW + 4, c.y + 58);
      ctx.restore();

      /* ---------- 歌词 ---------- */
      const lx = tx + 10;
      ctx.save();
      ctx.fillStyle = '#46595E';
      ctx.font = f(11, 400);
      setLS(ctx, 1.2);
      ctx.fillText(this.data.lyric0, lx, c.y + 104);

      ctx.fillStyle = C.teal;
      ctx.shadowColor = 'rgba(67,232,200,0.6)';
      ctx.shadowBlur = 8;
      rr(ctx, lx - 11, c.y + 121, 2.5, 18, 1.25);
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.fillStyle = '#F2FBFA';
      ctx.font = f(17, 400);
      setLS(ctx, 1.6);
      ctx.fillText(this.data.lyric1, lx, c.y + 140);

      ctx.fillStyle = '#75898E';
      ctx.font = f(13, 400);
      setLS(ctx, 1.2);
      ctx.fillText(this.data.lyric2, lx, c.y + 168);

      ctx.fillStyle = '#46595E';
      ctx.font = f(11.5, 400);
      ctx.fillText(this.data.lyric3, lx, c.y + 192);
      ctx.restore();

      /* ---------- 频谱条 ---------- */
      const eqX = c.x + 22, eqW = c.w - 44, eqY = c.y + 214, eqH = 40;
      const bars = 42, bw = 5, gapB = (eqW - bars * bw) / (bars - 1);
      ctx.save();
      for (let i = 0; i < bars; i++) {
        const hh = 6 + (eqH - 6) *
          (0.35 + 0.65 * Math.abs(Math.sin(t * 2.4 + i * 0.55))) *
          (0.55 + 0.45 * Math.sin(i * 0.9));
        const a = 0.16 + 0.30 * (hh / eqH);
        rr(ctx, eqX + i * (bw + gapB), eqY + eqH - hh, bw, hh, 2.5);
        ctx.fillStyle = `rgba(67,232,200,${a.toFixed(3)})`;
        ctx.fill();
      }
      ctx.restore();

      /* ---------- 进度 ---------- */
      const px = c.x + 22, pw = c.w - 44, py = c.y + 288;

      rr(ctx, px, py, pw, 3, 1.5);
      ctx.fillStyle = 'rgba(255,255,255,0.08)'; ctx.fill();

      const fw = pw * (this.data.mediaPct / 100);
      if (fw > 0.5) {
        const pg = ctx.createLinearGradient(px, 0, px + fw, 0);
        pg.addColorStop(0, '#1E8E7C');
        pg.addColorStop(1, C.teal);
        rr(ctx, px, py, fw, 3, 1.5);
        ctx.fillStyle = pg;
        ctx.shadowColor = 'rgba(67,232,200,0.5)'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.shadowBlur = 0;

        ctx.beginPath(); ctx.arc(px + fw, py + 1.5, 3.2, 0, 6.2832);
        ctx.fillStyle = '#EAFFFA';
        ctx.shadowColor = 'rgba(67,232,200,0.9)'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.shadowBlur = 0;
      }

      ctx.save();
      ctx.font = f(9, 400);
      setLS(ctx, 0.6);
      ctx.fillStyle = C.dim3;
      ctx.fillText(this.data.mediaTime, px, c.y + 306);
      ctx.textAlign = 'right';
      ctx.fillText(this.data.mediaDur, px + pw, c.y + 306);
      ctx.restore();
    }

    /* --------------------------- 档位 + 混动模式 --------------------------- */
    drawGear(ctx) {
      const c = L.gear;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      const cyC = c.y + c.h / 2;

      /* 档位字母 */
      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = '#F0FBFA';
      ctx.font = f(24, 600);
      setLS(ctx, 1);
      ctx.fillText(this.data.gear || 'D', c.x + 34, cyC + 1);
      ctx.restore();

      ctx.beginPath();
      ctx.moveTo(c.x + 58, c.y + 14);
      ctx.lineTo(c.x + 58, c.y + c.h - 14);
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      /* 混动模式胶囊 */
      const modes = this.data.modes || [];
      const padL = c.x + 70, padR = c.x + c.w - 16;
      const n = modes.length || 1;
      const gap = 8;
      const cw = (padR - padL - gap * (n - 1)) / n;
      const ch = 26;
      const cy0 = cyC - ch / 2;

      modes.forEach((m, i) => {
        const x = padL + (cw + gap) * i;
        const on = m === this.data.driveMode;

        rr(ctx, x, cy0, cw, ch, 8);
        if (on) {
          const g = ctx.createLinearGradient(0, cy0, 0, cy0 + ch);
          g.addColorStop(0, rgba(C.teal, 0.24));
          g.addColorStop(1, rgba(C.teal, 0.08));
          ctx.fillStyle = g;
        } else {
          ctx.fillStyle = 'rgba(255,255,255,0.035)';
        }
        ctx.fill();
        ctx.strokeStyle = on ? rgba(C.teal, 0.55) : 'rgba(120,170,180,0.16)';
        ctx.lineWidth = 1;
        ctx.stroke();

        ctx.save();
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = on ? '#B6FFF2' : '#6E8287';
        ctx.font = f(11.5, on ? 500 : 400);
        setLS(ctx, 1.8);
        if (on) {
          ctx.shadowColor = rgba(C.teal, 0.5);
          ctx.shadowBlur = 8;
        }
        ctx.fillText(m, x + cw / 2, cy0 + ch / 2 + 0.5);
        ctx.restore();

      });
    }
  }

  /* ------------------------------ 导出 / 自动挂载 ------------------------------ */
  /* ------------------------------ 导出 / 自动挂载 ------------------------------ */
  global.HUD = HUD;

  /*
   * 车机（Frida）环境适配（2026-11-10）：
   *
   * 原版会自动 createElement('canvas') 挂到 document.body。
   * Frida 里没有 DOM，canvas 由 dash_bridge.js 手动 new HUD(adapter) 创建。
   * 所以只在真的能 createElement 出带 getContext 的对象时才自动 boot。
   */
  function boot() {
    if (typeof document === 'undefined') return;
    if (!document.createElement) return;
    if (document.getElementById && document.getElementById('hud')) return;
    const c = document.createElement('canvas');
    if (!c || !c.getContext) return;
    c.id = 'hud';
    document.body.appendChild(c);
    document.body.style.margin = '0';
    document.body.style.background = '#000';
    document.body.style.overflow = 'hidden';
    document.body.style.height = '100vh';
    const hud = new HUD(c);
    hud.start();
    global.hud = hud;
  }
  if (typeof document !== 'undefined') {
    try { boot(); } catch (e) { /* Frida 环境：静默跳过 */ }
  }
})(typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : this));

/* ===== dash_bridge.js ===== */
/**
 * dash_bridge.js —— 把 dashboard.js 接到 Android Canvas 上
 *
 * dashboard.js 依赖浏览器环境（window / document / requestAnimationFrame /
 * performance.now / ResizeObserver），Frida 里没有。
 *
 * 这个文件做三件事：
 *   ① 提供这些全局对象的桩
 *   ② 建 Canvas2D 适配器 + Android Bitmap
 *   ③ 每帧调 hud 绘制，然后把 Bitmap 交给投屏链
 *
 * 用法（在 map_to_dash_prod.js 里）：
 *   dashInit();                  // 初始化（建 Bitmap、加载 dashboard.js）
 *   dashFrame();                 // 每帧调一次（在 PixelCopy 回调里）
 *   dashSetData({speed: 96});    // 更新数据
 */

var DASH = {
    inited: false,
    hud: null,
    c2d: null,
    bmp: null,
    err: '',
    lastFrameAt: 0,
    fps: 0,
    _frames: 0,
    _fpsT: 0,
    /* 单帧超时保护：超过这个毫秒数就跳过绘制，避免拖垮主线程 */
    maxFrameMs: 25
};

/* ══════════════════════════════════════════════════════════════
 * ① 浏览器环境桩
 *
 * dashboard.js 用到的：
 *   window（global）
 *   document（getElementById / createElement / body）
 *   requestAnimationFrame
 *   performance.now
 *   ResizeObserver
 *   addEventListener / removeEventListener
 *   devicePixelRatio / innerWidth / innerHeight
 * ══════════════════════════════════════════════════════════════ */
function dashInstallEnv() {
    if (typeof globalThis === 'undefined') return;

    /* requestAnimationFrame：Frida 里没有，用定时器模拟
     *
     * dashboard.js 的 loop() 里最后会调 requestAnimationFrame(this.loop)，
     * 但我们不想让它自己跑（我们要手动控制节奏），
     * 所以首次调用返回 false，让 start() 里的循环不启动。
     */
    if (typeof globalThis.requestAnimationFrame === 'undefined') {
        globalThis.requestAnimationFrame = function (cb) {
            /* 不真正调度，返回 0 告诉调用方"没排上" */
            return 0;
        };
    }
    if (typeof globalThis.cancelAnimationFrame === 'undefined') {
        globalThis.cancelAnimationFrame = function (id) {};
    }

    /* performance.now（Frida 有 Date.now，但没有 performance） */
    if (typeof globalThis.performance === 'undefined') {
        globalThis.performance = { now: function () { return Date.now(); } };
    } else if (typeof globalThis.performance.now !== 'function') {
        globalThis.performance.now = function () { return Date.now(); };
    }

    /* document 桩（dashboard.js 的 boot() 会查 getElementById） */
    if (typeof globalThis.document === 'undefined') {
        globalThis.document = {
            readyState: 'complete',
            getElementById: function () { return null; },
            createElement: function () { return { style: {} }; },
            body: { style: {}, appendChild: function () {} },
            addEventListener: function () {},
            removeEventListener: function () {}
        };
    }

    /* ResizeObserver 桩（不监听尺寸变化） */
    if (typeof globalThis.ResizeObserver === 'undefined') {
        globalThis.ResizeObserver = function (cb) {
            this.observe = function () {};
            this.disconnect = function () {};
        };
    }

    /* 窗口尺寸 / DPR */
    if (typeof globalThis.innerWidth === 'undefined') globalThis.innerWidth = 1920;
    if (typeof globalThis.innerHeight === 'undefined') globalThis.innerHeight = 532;
    if (typeof globalThis.devicePixelRatio === 'undefined') globalThis.devicePixelRatio = 1;

    /* addEventListener */
    if (typeof globalThis.addEventListener === 'undefined') {
        globalThis.addEventListener = function () {};
        globalThis.removeEventListener = function () {};
    }
}

/* ══════════════════════════════════════════════════════════════
 * ② 初始化
 * ══════════════════════════════════════════════════════════════ */
function dashInit() {
    if (DASH.inited) return true;
    try {
        dashInstallEnv();

        /* 建 Android Bitmap */
        var Bitmap = Java.use('android.graphics.Bitmap');
        var Config = Java.use('android.graphics.Bitmap$Config');
        var W = (typeof CFG !== 'undefined' && CFG.W) ? CFG.W : 1920;
        var H = (typeof CFG !== 'undefined' && CFG.H) ? CFG.H : 532;
        DASH.bmp = Bitmap.createBitmap(W, H, Config.ARGB_8888.value);

        /* 建 Canvas2D 适配器 */
        DASH.c2d = new Canvas2D(DASH.bmp, W, H);
        if (!DASH.c2d._ready) {
            DASH.err = 'Canvas2D 初始化失败: ' + DASH.c2d._err;
            log('DASH: ' + DASH.err);
            return false;
        }

        DASH.inited = true;
        log('DASH: 初始化完成 ' + W + 'x' + H);
        return true;
    } catch (e) {
        DASH.err = String(e).substring(0, 120);
        log('DASH: 初始化异常 ' + DASH.err);
        return false;
    }
}

/* ══════════════════════════════════════════════════════════════
 * ③ 创建 HUD 实例
 * ══════════════════════════════════════════════════════════════ */
function dashCreateHud() {
    if (!DASH.inited) {
        if (!dashInit()) return null;
    }
    if (DASH.hud !== null) return DASH.hud;

    try {
        if (typeof HUD === 'undefined') {
            log('DASH: HUD 类不存在（dashboard.js 没加载？）');
            return null;
        }

        /* dashboard.js 的构造函数签名：new HUD(canvas, opts)
         *
         * 它内部会调 canvas.getContext('2d')，我们的适配器实现了。
         * 但 fix 一点：构造函数会读 canvas.parentElement，我们的适配器返回 null，
         * 所以 ResizeObserver 那段会跳过（正确）。
         */
        var hud = new HUD(DASH.c2d, {
            /* 关掉内置的演示模拟，用真实数据 */
            autoSpeed: false,
            autoPower: false
        });
        hud.autoSpeed = false;
        hud.autoPower = false;

        /* 不调 hud.start() —— 那会启动它自己的 rAF 循环。
         * 我们手动调 hud.draw(t) 控制节奏。 */
        hud.running = false;
        hud.time = 0;
        hud.last = 0;

        DASH.hud = hud;
        log('DASH: HUD 实例已创建');
        return hud;
    } catch (e) {
        DASH.err = String(e).substring(0, 120);
        log('DASH: 创建 HUD 失败 ' + DASH.err);
        return null;
    }
}

/* ══════════════════════════════════════════════════════════════
 * ④ 每帧绘制
 * ══════════════════════════════════════════════════════════════ */
function dashFrame() {
    if (!DASH.inited) {
        if (!dashInit()) return false;
    }
    if (DASH.hud === null) {
        if (!dashCreateHud()) return false;
    }

    var now = Date.now();
    /* 累加时间（dashboard.js 用 this.time 做动画相位） */
    if (DASH.hud.last > 0) {
        var dt = Math.min((now - DASH.hud.last) / 1000, 0.1);
        DASH.hud.time += dt;
    }
    DASH.hud.last = now;

    var t0 = now;
    try {
        /* 直接调 dashboard.js 的 draw(t)
         *
         * 它的 draw() 里会：
         *   ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
         *   ctx.clearRect(0, 0, W, H)
         *   ... 14 个 draw* 方法
         */
        DASH.hud.draw(DASH.hud.time);
        DASH.c2d.flush();
    } catch (e) {
        DASH.err = String(e).substring(0, 120);
        if (!DASH._errShown) {
            DASH._errShown = true;
            log('DASH: draw 失败 ' + DASH.err);
        }
        return false;
    }

    var cost = Date.now() - t0;
    DASH.lastFrameAt = now;
    DASH._frames++;
    /* 记录最近一帧 draw 耗时，供主流程 STAT 打印 */
    DASH.lastDrawMs = cost;
    if (cost > DASH.maxFrameMs) DASH.maxFrameMs = cost;
    if (now - DASH._fpsT >= 1000) {
        DASH.fps = DASH._frames;
        DASH._frames = 0;
        DASH._fpsT = now;
    }

    if (cost > DASH.maxFrameMs && !DASH._slowShown) {
        DASH._slowShown = true;
        log('DASH: 单帧耗时 ' + cost + 'ms（超过 ' + DASH.maxFrameMs + 'ms 阈值）');
    }
    return true;
}

/* ══════════════════════════════════════════════════════════════
 * ⑤ 数据接口
 * ══════════════════════════════════════════════════════════════ */
function dashSetData(obj) {
    if (DASH.hud === null) return;
    try {
        DASH.hud.setData(obj);
    } catch (e) {}
}

/** 从车辆数据同步（vehicle.js） */
function dashSyncVehicle() {
    if (DASH.hud === null) return;
    if (typeof vehRead !== 'function') return;
    try {
        var d = vehRead();
        DASH.hud.setData({
            speed: Math.round(d.speed),
            power: Math.round(d.power),
            evRange: Math.round(d.evRange),
            evCap: Math.round(d.evCap || 180),
            fuelRange: Math.round(d.fuelRange || 1370),
            fuelCap: Math.round(d.fuelCap || 1560),
            gear: d.gear || 'D'
        });
    } catch (e) {}
}

/** 从媒体数据同步（media.js） */
function dashSyncMedia() {
    if (DASH.hud === null) return;
    if (typeof medPoll !== 'function') return;
    try {
        var m = medPoll();
        var L = (typeof MEDIA !== 'undefined') ? MEDIA.lyric : null;
        DASH.hud.setData({
            mediaPlaying: m.playing,
            mediaTitle: m.title || '',
            mediaArtist: m.artist || '',
            mediaAlbum: m.album || '',
            lyric0: L ? L.text0 : '',
            lyric1: L ? L.text1 : '',
            lyric2: L ? L.text2 : '',
            lyric3: L ? L.text3 : ''
        });
    } catch (e) {}
}

/* ══════════════════════════════════════════════════════════════
 * ⑥ 数据源切换：mock / 真实
 *
 * dashboard.js 默认数据是演示值（夜航西飞那个）。
 * 这里提供 mock 模式，方便不接车机时看效果。
 * ══════════════════════════════════════════════════════════════ */
var DASH_MOCK = {
    on: true,
    t: 0
};

function dashMockTick() {
    if (!DASH_MOCK.on || DASH.hud === null) return;
    DASH_MOCK.t += 0.016;
    var t = DASH_MOCK.t;
    /* 车速做正弦摆动，功率跟车速走 */
    var spd = 96 + Math.sin(t * 0.31) * 34 + Math.sin(t * 1.37) * 9;
    DASH.hud.setData({
        speed: Math.max(0, Math.round(spd)),
        power: Math.round(Math.max(0, spd * 1.2 - 10)),
        evRange: Math.round(130 + Math.sin(t * 0.05) * 5),
        fuelRange: Math.round(1370 + Math.sin(t * 0.03) * 20),
        noa: true,
        gear: 'D',
        driveMode: '纯电'
    });
}

/** 状态 */
function dashStatus() {
    return 'inited=' + DASH.inited
         + ' fps=' + DASH.fps
         + ' draws=' + (DASH.c2d ? DASH.c2d.stats.draws : 0)
         + (DASH.err ? ' err=' + DASH.err : '');
}

/* ===== vehicle.js ===== */
/**
 * vehicle.js —— 车辆数据获取（五路并联）
 *
 * 架构参考 GD-Bridge 的 VehicleAdapter：
 *   多个上游通道顺序尝试，哪个通就用哪个，全不通降级到 mock。
 *
 * 通道优先级（越前面越可靠、越少权限）：
 *   ① VhalChannel       反射 IVehicle.getService()，直接订阅 VHAL
 *                        绕开 CarService 的权限检查
 *   ② CarPropertyChannel Car.createCar() + CarPropertyManager
 *                        （已实测能拿到 917 个属性，但读值可能被拒）
 *   ③ EcarxBinderChannel getService("ecarxcar_service") / ("car_signal")
 *   ④ EcarxXuiChannel   反射 com.ecarx.xui.adaptapi.car.Car
 *   ⑤ BuiltinChannel    按实测拿到的 propertyId 直读（走 ① 的通道）
 *   ⑥ MockChannel       台架/演示用
 *
 * 关键设计：
 *   · 每个通道独立 try/catch，一路失败不影响其他
 *   · 归一化成统一结构 vehicleRead()，上层不关心底层用了哪条路
 *   · 缓存 propertyId（第一次枚举 917 个属性要几十毫秒）
 *   · 只读，不做任何 setter（避免误改车况）
 *
 * 用法：
 *   vehInit();                    // 启动时调一次，探测可用通道
 *   var d = vehRead();            // 每帧/每秒调，返回归一化数据
 *   log(vehStatus());             // 调试：打印当前用的通道
 */

var VEH = {
    inited: false,
    channel: 'none',          // 'vhal' | 'cpm' | 'ecarx' | 'xui' | 'mock'
    cpm: null,                // CarPropertyManager 实例
    car: null,                // Car 实例
    vhal: null,               // IVehicle 代理（如果走 ①）
    propIds: {},              // 符号名 → propertyId
    err: '',
    readCount: 0,
    failCount: 0,
    lastLog: 0,

    /* 上一次成功读到的值（读取失败时沿用，避免仪表跳 0） */
    last: {
        speed: 0, power: 0, evRange: 130, evPct: 90,
        fuelRange: 1370, fuelPct: 88, tempC: 21,
        gear: 'D', odometer: 0, soc: 0
    }
};

/* ── 属性符号名 → 候选 propertyId 列表 ───────────────────────────
 *
 * 同一个量在不同车型/固件上 ID 不同，所以给一组候选，
 * 哪个能读到用哪个。
 *
 * 前几个是我们在这台车（SE1000）上实测到的：
 *   291504647  PERF_VEHICLE_SPEED
 *   291504648  PERF_VEHICLE_SPEED_DISPLAY
 *   291504904  RANGE_REMAINING
 *   291504390  INFO_EV_BATTERY_CAPACITY
 *   291504388  INFO_FUEL_CAPACITY
 *   358614274  HVAC_TEMPERATURE_CURRENT
 *
 * 后面的是 AAOS 标准值 / vendor 常见值（来自 GD-Bridge 逆向报告）。
 */
var VEH_PROPS = {
    speed:       [291504647, 291504648, 1048832],      /* PERF_VEHICLE_SPEED / SENSOR_SPEED */
    evRange:     [291504904],                          /* RANGE_REMAINING */
    evCap:       [291504390],                          /* INFO_EV_BATTERY_CAPACITY */
    fuelRange:   [291504904],                          /* 同 RANGE_REMAINING，按车型区分 */
    fuelCap:     [291504388],                          /* INFO_FUEL_CAPACITY */
    tempC:       [358614274, 358614275],               /* HVAC_TEMPERATURE_CURRENT/SET */
    odometer:    [291504644],                          /* PERF_ODOMETER */
    gear:        [289408000, 289408001],               /* GEAR_AAOS / GEAR_DISPLAY */
    soc:         [291504389, 291504390],               /* EV 电量百分比 / 容量 */
    power:       []                                     /* 功率一般读不到，走估算 */
};

/* ── 工具 ─────────────────────────────────────────────────────── */

/** 安全的 Java.use，失败返回 null */
function vehJava(fq) {
    try { return Java.use(fq); } catch (e) { return null; }
}

/** 反射调用实例方法 */
function vehReflect(obj, method, types, args) {
    try {
        var cls = obj.getClass();
        var m = cls.getMethod(method, types || []);
        return m.invoke(obj, args || []);
    } catch (e) {
        return null;
    }
}

/* ── 通道 ①：VHAL HIDL 反射 ─────────────────────────────────────
 *
 * 直接拿 IVehicle binder，绕开 CarService 的权限检查。
 *
 * AAOS 的 HIDL 接口：
 *   android.hardware.automotive.vehicle.V2_0.IVehicle
 *   getService() → IVehicle
 *   getAllPropConfigs() → vec<VehiclePropConfig>
 *   get(VehiclePropValue) → VehiclePropValue
 *   subscribe(IVehicleCallback, vec<SubscribeOptions>)
 *
 * 注意：HIDL 的 Java 绑定在不同 ROM 上类路径可能不同，
 *       所以全部用 Class.forName 反射。
 */
function vehTryVhal() {
    try {
        var IVehicle = null;
        var paths = [
            'android.hardware.automotive.vehicle.V2_0.IVehicle',
            'android.hardware.automotive.vehicle.V2_1.IVehicle',
            'android.hardware.automotive.vehicle.V3_0.IVehicle'
        ];
        for (var i = 0; i < paths.length; i++) {
            IVehicle = vehJava(paths[i]);
            if (IVehicle !== null) {
                log('VEH: 找到 ' + paths[i]);
                break;
            }
        }
        if (IVehicle === null) {
            log('VEH: HIDL IVehicle 不可用（可能不是 HIDL 版本）');
            return false;
        }

        /* 拿 binder */
        var vhal = null;
        try {
            vhal = IVehicle.getService();
        } catch (e) {
            log('VEH: IVehicle.getService() 失败: ' + String(e).substring(0, 60));
            return false;
        }
        if (vhal === null) {
            log('VEH: getService() 返回 null');
            return false;
        }

        VEH.vhal = vhal;
        VEH.channel = 'vhal';
        log('VEH: VHAL 通道就绪');
        return true;
    } catch (e) {
        log('VEH: VHAL 通道异常 ' + String(e).substring(0, 80));
        return false;
    }
}

/* ── 通道 ②：CarPropertyManager ─────────────────────────────────
 *
 * 已实测：Car.createCar(app) → getCarManager("property") 能拿到。
 * getPropertyList() 能拿到 917 个属性（需要反射，桥接层直接调会报错）。
 */
function vehTryCarProperty() {
    try {
        var Car = vehJava('android.car.Car');
        if (Car === null) {
            log('VEH: android.car.Car 不可用');
            return false;
        }
        var app = Java.use('android.app.ActivityThread').currentApplication();
        var car = Car.createCar(app);
        if (car === null) {
            log('VEH: createCar 返回 null');
            return false;
        }
        VEH.car = car;

        var cpm = car.getCarManager('property');
        if (cpm === null) {
            cpm = car.getCarManager('android.car.CarPropertyManager');
        }
        if (cpm === null) {
            log('VEH: getCarManager("property") 返回 null');
            return false;
        }
        VEH.cpm = cpm;
        VEH.channel = 'cpm';
        log('VEH: CarPropertyManager 就绪');

        /* 顺便枚举一下属性列表，建立符号名映射 */
        vehBuildPropMap();
        return true;
    } catch (e) {
        log('VEH: CarProperty 通道异常 ' + String(e).substring(0, 80));
        return false;
    }
}

/** 枚举 getPropertyList()，按 getConfigString 建立名字 → ID 映射 */
function vehBuildPropMap() {
    if (!VEH.cpm) return;
    try {
        var cls = VEH.cpm.getClass();
        var m = cls.getMethod('getPropertyList', []);
        var raw = m.invoke(VEH.cpm, []);
        var list = Java.cast(raw, Java.use('java.util.List'));
        log('VEH: 枚举到 ' + list.size() + ' 个属性');

        var need = {
            'PERF_VEHICLE_SPEED': 'speed',
            'PERF_VEHICLE_SPEED_DISPLAY': 'speed',
            'RANGE_REMAINING': 'evRange',
            'INFO_EV_BATTERY_CAPACITY': 'evCap',
            'INFO_FUEL_CAPACITY': 'fuelCap',
            'HVAC_TEMPERATURE_CURRENT': 'tempC',
            'PERF_ODOMETER': 'odometer'
        };

        for (var i = 0; i < list.size(); i++) {
            try {
                var cfg = list.get(i);
                var c2 = cfg.getClass();
                var id = c2.getMethod('getPropertyId', []).invoke(cfg, []);
                var nm = String(c2.getMethod('getConfigString', []).invoke(cfg, []));
                if (need[nm] && !VEH.propIds[need[nm]]) {
                    VEH.propIds[need[nm]] = Number(id);
                    log('VEH: ' + nm + ' → ' + id + ' (' + need[nm] + ')');
                }
            } catch (e) {}
        }
    } catch (e) {
        log('VEH: 枚举属性失败 ' + String(e).substring(0, 80));
    }
}

/* ── 通道 ③：ecarxcar_service binder ──────────────────────────── */
function vehTryEcarxBinder() {
    try {
        var SM = vehJava('android.os.ServiceManager');
        if (SM === null) return false;
        var list = SM.listServices();
        var found = [];
        for (var i = 0; i < list.length; i++) {
            var n = String(list[i]);
            if (/ecarx.*car|car.*signal|vehicle/i.test(n)) {
                found.push(n);
            }
        }
        if (found.length === 0) {
            log('VEH: 没找到 ecarx 车辆服务');
            return false;
        }
        log('VEH: 找到候选服务: ' + found.join(', '));
        VEH.ecarxServices = found;
        /* 具体接口形态未知，这里只记录，读取时再试 */
        return false;   /* 暂不作为主通道 */
    } catch (e) {
        return false;
    }
}

/* ── 通道 ④：ECARX XUI SDK ───────────────────────────────────── */
function vehTryEcarxXui() {
    try {
        var XuiCar = vehJava('com.ecarx.xui.adaptapi.car.Car');
        if (XuiCar === null) {
            log('VEH: ECARX XUI SDK 不可用');
            return false;
        }
        var app = Java.use('android.app.ActivityThread').currentApplication();
        var xcar = XuiCar.create(app);
        if (xcar === null) return false;

        var func = null;
        try { func = xcar.getICarFunction(); } catch (e) {}
        if (func === null) {
            try { func = xcar.getSensorManager(); } catch (e) {}
        }
        if (func === null) return false;

        VEH.xuiFunc = func;
        VEH.channel = 'xui';
        log('VEH: ECARX XUI 通道就绪');
        return true;
    } catch (e) {
        return false;
    }
}

/* ── 初始化：按优先级试各通道 ───────────────────────────────── */
function vehInit() {
    if (VEH.inited) return VEH.channel;

    log('VEH: 开始探测车辆数据通道');

    /* 先试 ②（已实测可用） */
    if (vehTryCarProperty()) {
        VEH.inited = true;
        log('VEH: 通道选定 = cpm');
        return VEH.channel;
    }

    /* 再试 ① */
    if (vehTryVhal()) {
        VEH.inited = true;
        log('VEH: 通道选定 = vhal');
        return VEH.channel;
    }

    /* ③④ 只探测不主用 */
    vehTryEcarxBinder();
    if (vehTryEcarxXui()) {
        VEH.inited = true;
        return VEH.channel;
    }

    /* 全失败 → mock */
    VEH.channel = 'mock';
    VEH.inited = true;
    log('VEH: 全部通道失败，降级 mock');
    return VEH.channel;
}

/* ── 读取单个属性 ─────────────────────────────────────────────── */

/**
 * 通过 CarPropertyManager 读 float。
 * 依次试候选 ID，第一个成功的就用。
 */
function vehReadFloat(symbol) {
    if (!VEH.cpm) return null;
    var ids = VEH.propIds[symbol] ? [VEH.propIds[symbol]] : (VEH_PROPS[symbol] || []);

    for (var i = 0; i < ids.length; i++) {
        var pid = ids[i];
        try {
            /* getFloatProperty(int, int) —— 第二个参数是 areaId，用 0 */
            var v = VEH.cpm.getFloatProperty(pid, 0);
            if (v !== null && !isNaN(v)) return Number(v);
        } catch (e) {
            /* 类型不对就试 int */
            try {
                var v2 = VEH.cpm.getIntProperty(pid, 0);
                if (v2 !== null) return Number(v2);
            } catch (e2) {}
        }
    }
    return null;
}

/** 读 int（档位这种枚举值） */
function vehReadInt(symbol) {
    if (!VEH.cpm) return null;
    var ids = VEH.propIds[symbol] ? [VEH.propIds[symbol]] : (VEH_PROPS[symbol] || []);
    for (var i = 0; i < ids.length; i++) {
        try {
            var v = VEH.cpm.getIntProperty(ids[i], 0);
            if (v !== null) return Number(v);
        } catch (e) {}
    }
    return null;
}

/* GEAR 枚举 → 字母（AAOS VehicleGear） */
function vehGearLetter(g) {
    if (g === null) return null;
    /* AAOS: 1=PARK 2=REVERSE 4=NEUTRAL 8=DRIVE 16=LOW */
    if (g & 1)  return 'P';
    if (g & 2)  return 'R';
    if (g & 4)  return 'N';
    if (g & 8)  return 'D';
    if (g & 16) return 'L';
    return null;
}

/* ── 归一化读取 ───────────────────────────────────────────────── */

/**
 * 读一次全部数据，返回和 ui_android.js 的 UIA.data 对齐的结构。
 * 读不到的沿用 last（避免仪表跳 0）。
 */
function vehRead() {
    if (!VEH.inited) vehInit();

    var d = {};
    var ok = 0, fail = 0;

    function setNum(key, val, scale) {
        if (val === null || val === undefined) { fail++; return; }
        var v = val * (scale || 1);
        VEH.last[key] = v;
        d[key] = v;
        ok++;
    }

    if (VEH.channel === 'cpm' || VEH.channel === 'vhal') {
        setNum('speed',    vehReadFloat('speed'));
        setNum('evRange',  vehReadFloat('evRange'));
        setNum('evCap',    vehReadFloat('evCap'));
        setNum('fuelCap',  vehReadFloat('fuelCap'));
        setNum('tempC',    vehReadFloat('tempC'));
        setNum('odometer', vehReadFloat('odometer'));

        var g = vehReadInt('gear');
        var gl = vehGearLetter(g);
        if (gl) { d.gear = gl; VEH.last.gear = gl; ok++; } else { fail++; }
    }

    /* 功率读不到就从车速估算（爬坡/加速时功率高） */
    if (d.speed !== undefined) {
        d.power = Math.round(Math.max(0, d.speed * 1.4 - 20));
        VEH.last.power = d.power;
    }

    /* 续航百分比 */
    if (d.evRange !== undefined && d.evCap !== undefined && d.evCap > 0) {
        d.evPct = Math.round(d.evRange / d.evCap * 100);
        VEH.last.evPct = d.evPct;
    }
    if (d.fuelCap !== undefined && d.fuelCap > 0) {
        /* 燃油百分比需要当前油量，读不到就沿用 */
        d.fuelPct = VEH.last.fuelPct;
    }

    /* 补全读不到的字段 */
    for (var k in VEH.last) {
        if (d[k] === undefined) d[k] = VEH.last[k];
    }

    VEH.readCount++;
    if (fail > 0) VEH.failCount++;

    d._channel = VEH.channel;
    d._ok = ok;
    d._fail = fail;
    return d;
}

/** 调试：当前状态 */
function vehStatus() {
    return 'channel=' + VEH.channel
         + ' reads=' + VEH.readCount
         + ' fails=' + VEH.failCount
         + ' props=' + Object.keys(VEH.propIds).length
         + (VEH.err ? ' err=' + VEH.err : '');
}

/**
 * 把车辆数据写进 UI 层。
 * 上层每帧/每秒调一次即可。
 */
function vehSyncToUi() {
    if (typeof UIA === 'undefined') return;
    var d = vehRead();
    UIA.data.speed     = Math.round(d.speed);
    UIA.data.power     = Math.round(d.power);
    UIA.data.evRange   = Math.round(d.evRange);
    UIA.data.evCap     = Math.round(d.evCap || UIA.data.evCap);
    UIA.data.fuelRange = Math.round(d.fuelRange || UIA.data.fuelRange);
    UIA.data.fuelCap   = Math.round(d.fuelCap || UIA.data.fuelCap);
    UIA.data.tempC     = Math.round(d.tempC);
    UIA.data.gear      = d.gear;
    /* 同步给仪表显示的 outdoor/indoor */
    UIA.data.outdoor   = String(Math.round(d.tempC));
}

/* ===== media.js ===== */
/**
 * media.js —— 媒体信息与歌词
 *
 * 目标：拿到 歌名 / 歌手 / 专辑 / 封面 / 播放状态 / 歌词
 *
 * 难点（已实测）：
 *   · MediaSessionManager.getActiveSessions() 需要 MEDIA_CONTENT_CONTROL
 *     或 NotificationListener 授权，地图进程没有 → 被拒
 *   · 歌词在汽水音乐（com.luna.music.car）的私有缓存里，需要 root
 *   · EVCC（com.kooo.evcc）有 NotificationListener，能读到，但那是第三方进程
 *
 * 所以这里分三层：
 *   ① 优先 MediaSessionManager（如果这个 ROM 没卡权限）
 *   ② 降级 MediaBrowserService 绑定（公开接口，不需要权限）
 *   ③ 再降级：读 /data/media 或 sr_media 的 SharedPreferences
 *   ④ 全失败：显示"暂无媒体"（不影响其他功能）
 *
 * 歌词同理：
 *   ① 读汽水缓存（如果 root 可读）
 *   ② 从 MediaMetadata 的 extras 里找（有些播放器会塞进去）
 *   ③ 没有就不显示（UI 层留空）
 *
 * 关键：全部只读，不做任何控制（不 play/pause/next），避免干扰用户。
 */

var MEDIA = {
    inited: false,
    channel: 'none',        // 'session' | 'browser' | 'prefs' | 'none'
    ctrl: null,             // MediaController
    browser: null,          // MediaBrowser（如果走 ②）
    err: '',

    /* 当前状态 */
    state: {
        playing: false,
        title: '',
        artist: '',
        album: '',
        duration: 0,
        position: 0,
        pkg: ''
    },

    /* 歌词 */
    lyric: {
        lines: [],          // [{t: 毫秒, text: '...'}]
        index: -1,
        text0: '', text1: '', text2: '', text3: ''
    },

    lastPoll: 0,
    failCount: 0
};

/* ── 工具 ─────────────────────────────────────────────────────── */
function medJava(fq) {
    try { return Java.use(fq); } catch (e) { return null; }
}

/** 反射取方法 */
function medReflect(obj, method, types) {
    try {
        var cls = obj.getClass();
        var m = cls.getMethod(method, types || []);
        return m.invoke(obj, []);
    } catch (e) {
        return null;
    }
}

/* ── 通道 ①：MediaSessionManager ────────────────────────────────
 *
 * 需要 MEDIA_CONTENT_CONTROL 或 NotificationListener。
 * 地图进程大概率被拒，但值得试一次（不同 ROM 权限策略不同）。
 */
function medTrySession() {
    try {
        var app = Java.use('android.app.ActivityThread').currentApplication();
        var msm = app.getSystemService('media_session');
        if (msm === null) {
            log('MEDIA: MediaSessionManager 不可用');
            return false;
        }

        var CM = Java.use('android.content.ComponentName');
        var list = null;
        try {
            var m = msm.getClass().getMethod('getActiveSessions', [CM.class]);
            var raw = m.invoke(msm, [null]);
            list = Java.cast(raw, Java.use('java.util.List'));
        } catch (e) {
            log('MEDIA: getActiveSessions 被拒（需要 MEDIA_CONTENT_CONTROL）');
            return false;
        }

        if (list === null || list.size() === 0) {
            log('MEDIA: 没有活跃的媒体会话');
            return false;
        }

        log('MEDIA: 找到 ' + list.size() + ' 个会话');
        for (var i = 0; i < list.size(); i++) {
            try {
                var c = list.get(i);
                var pkg = String(medReflect(c, 'getPackageName'));
                log('MEDIA:   [' + i + '] ' + pkg);
                /* 优先选正在播放的 */
                var pb = medReflect(c, 'getPlaybackState');
                if (pb !== null) {
                    var st = Number(medReflect(pb, 'getState'));
                    if (st === 3) {          /* STATE_PLAYING */
                        MEDIA.ctrl = c;
                        MEDIA.state.pkg = pkg;
                        break;
                    }
                }
                if (MEDIA.ctrl === null) {
                    MEDIA.ctrl = c;
                    MEDIA.state.pkg = pkg;
                }
            } catch (e) {}
        }

        if (MEDIA.ctrl === null) return false;
        MEDIA.channel = 'session';
        log('MEDIA: session 通道就绪，pkg=' + MEDIA.state.pkg);
        return true;
    } catch (e) {
        log('MEDIA: session 通道异常 ' + String(e).substring(0, 60));
        return false;
    }
}

/* ── 通道 ②：MediaBrowserService 绑定 ───────────────────────────
 *
 * 公开接口，任何 App 都能 bind，不需要特殊权限。
 * 需要知道目标 App 的 MediaBrowserService 组件名。
 */
function medTryBrowser() {
    try {
        var app = Java.use('android.app.ActivityThread').currentApplication();
        var pm = app.getPackageManager();

        /* 常见的车机音乐 App */
        var candidates = [
            'com.luna.music.car',        /* 汽水音乐车机版 */
            'ecarx.xsf.mediacenter',     /* 亿咖通媒体中心 */
            'com.geely.aimusicmv',
            'com.flyme.auto.mediacontrol',
            'com.tencent.qqmusiccar'
        ];

        /* 先查哪个装了 */
        var installed = [];
        for (var i = 0; i < candidates.length; i++) {
            try {
                pm.getPackageInfo(candidates[i], 0);
                installed.push(candidates[i]);
                log('MEDIA: 已安装 ' + candidates[i]);
            } catch (e) {}
        }
        if (installed.length === 0) {
            log('MEDIA: 没有找到已知的音乐 App');
            return false;
        }

        /* 查每个包的 MediaBrowserService 组件 */
        var Intent = Java.use('android.content.Intent');
        var queryIntent = Intent.$new('android.media.browse.MediaBrowserService');
        var services = pm.queryIntentServices(queryIntent, 0);
        var found = [];
        for (var j = 0; j < services.size(); j++) {
            try {
                var si = services.get(j);
                var pn = String(si.serviceInfo.packageName.value);
                var cn = String(si.serviceInfo.name.value);
                found.push({ pkg: pn, cls: cn });
                log('MEDIA: MediaBrowserService ' + pn + '/' + cn);
            } catch (e) {}
        }

        if (found.length === 0) {
            log('MEDIA: 没有导出的 MediaBrowserService');
            return false;
        }

        MEDIA.browserServices = found;
        /* 真正的绑定需要异步和 ConnectionCallback，这里只记录
         * （在 Frida 里做完整 bind 比较复杂，先留作后续增强） */
        return false;
    } catch (e) {
        log('MEDIA: browser 探测失败 ' + String(e).substring(0, 60));
        return false;
    }
}

/* ── 通道 ③：SharedPreferences（读播放状态快照）─────────────────
 *
 * 有些媒体 App 会把当前曲目写进 prefs，root 可读。
 */
function medTryPrefs() {
    try {
        var File = Java.use('java.io.File');
        var paths = [
            '/data/data/com.luna.music.car/shared_prefs',
            '/data/data/ecarx.xsf.mediacenter/shared_prefs'
        ];
        var found = false;
        for (var i = 0; i < paths.length; i++) {
            var f = File.$new(paths[i]);
            if (f.exists()) {
                log('MEDIA: prefs 目录存在 ' + paths[i]);
                found = true;
            }
        }
        if (found) MEDIA.prefsPaths = paths;
        return false;   /* 只记录，不作为主通道 */
    } catch (e) {
        return false;
    }
}

/* ── 初始化 ───────────────────────────────────────────────────── */
function medInit() {
    if (MEDIA.inited) return MEDIA.channel;
    log('MEDIA: 开始探测');

    if (medTrySession()) { MEDIA.inited = true; return MEDIA.channel; }
    medTryBrowser();
    medTryPrefs();

    MEDIA.channel = 'none';
    MEDIA.inited = true;
    log('MEDIA: 无可用通道，显示"暂无媒体"');
    return MEDIA.channel;
}

/* ── 读取当前媒体信息 ─────────────────────────────────────────── */
function medPoll() {
    if (!MEDIA.inited) medInit();
    if (MEDIA.channel !== 'session' || MEDIA.ctrl === null) return MEDIA.state;

    var now = Date.now();
    if (now - MEDIA.lastPoll < 500) return MEDIA.state;   /* 500ms 节流 */
    MEDIA.lastPoll = now;

    try {
        /* PlaybackState */
        var pb = medReflect(MEDIA.ctrl, 'getPlaybackState');
        if (pb !== null) {
            var st = Number(medReflect(pb, 'getState'));
            MEDIA.state.playing = (st === 3);
            MEDIA.state.position = Number(medReflect(pb, 'getPosition')) || 0;
        }

        /* MediaMetadata */
        var md = medReflect(MEDIA.ctrl, 'getMetadata');
        if (md !== null) {
            var t = medReflect(md, 'getString', null);
            /* getString(String) 需要参数，上面拿不到，重新取 */
            var cls = md.getClass();
            function getStr(key) {
                try {
                    var m = cls.getMethod('getString', [Java.use('java.lang.String').class]);
                    var v = m.invoke(md, [key]);
                    return v === null ? '' : String(v);
                } catch (e) { return ''; }
            }
            MEDIA.state.title  = getStr('android.media.metadata.TITLE');
            MEDIA.state.artist = getStr('android.media.metadata.ARTIST');
            MEDIA.state.album  = getStr('android.media.metadata.ALBUM');
            var dur = null;
            try {
                var m2 = cls.getMethod('getLong', [Java.use('java.lang.String').class]);
                dur = m2.invoke(md, ['android.media.metadata.DURATION']);
            } catch (e) {}
            if (dur !== null) MEDIA.state.duration = Number(dur);
        }
        MEDIA.failCount = 0;
    } catch (e) {
        MEDIA.failCount++;
        if (MEDIA.failCount <= 3) {
            log('MEDIA: poll 失败 ' + String(e).substring(0, 60));
        }
    }
    return MEDIA.state;
}

/* ── 歌词 ─────────────────────────────────────────────────────── */

/**
 * 尝试从汽水音乐缓存读歌词。
 *
 * 缓存路径（EVCC 的 BridgeService 用的就是这个思路）：
 *   /data/data/com.luna.music.car/files/lyric/
 *   /data/data/com.luna.music.car/cache/
 *
 * 需要 root。地图进程是 platform_app，可能连不上这些目录，
 * 但 root shell 能读。这里先检查可读性。
 */
function medTryLyricCache() {
    try {
        var File = Java.use('java.io.File');
        var dirs = [
            '/data/data/com.luna.music.car/files',
            '/data/data/com.luna.music.car/cache',
            '/data/data/com.luna.music.car/files/lyric'
        ];
        for (var i = 0; i < dirs.length; i++) {
            var d = File.$new(dirs[i]);
            if (!d.exists()) continue;
            var list = d.list();
            if (list === null) {
                log('MEDIA: 目录不可读 ' + dirs[i]);
                continue;
            }
            log('MEDIA: 歌词目录可读 ' + dirs[i] + ' (' + list.length + ' 项)');
            MEDIA.lyricDir = dirs[i];
            return true;
        }
    } catch (e) {}
    return false;
}

/**
 * 解析 LRC 格式歌词。
 *
 * 格式：
 *   [00:12.34]第一句
 *   [00:15.67]第二句
 *
 * 返回 [{t: 毫秒, text: '...'}]
 */
function medParseLrc(text) {
    var lines = [];
    if (!text) return lines;
    var arr = String(text).split('\n');
    var re = /\[(\d+):(\d+)(?:[.:](\d+))?\]\s*(.*)/;
    for (var i = 0; i < arr.length; i++) {
        var m = re.exec(arr[i]);
        if (!m) continue;
        var min = parseInt(m[1], 10);
        var sec = parseInt(m[2], 10);
        var ms = m[3] ? parseInt(m[3].padEnd(3, '0').substring(0, 3), 10) : 0;
        var t = min * 60000 + sec * 1000 + ms;
        var txt = (m[4] || '').trim();
        if (txt) lines.push({ t: t, text: txt });
    }
    lines.sort(function (a, b) { return a.t - b.t; });
    return lines;
}

/**
 * 按当前播放位置，更新 4 行歌词（上一句/当前/下一句/再下一句）。
 */
function medUpdateLyric(positionMs) {
    var L = MEDIA.lyric;
    if (!L.lines || L.lines.length === 0) {
        L.text0 = L.text1 = L.text2 = L.text3 = '';
        return;
    }
    /* 二分找当前句 */
    var lo = 0, hi = L.lines.length - 1, idx = -1;
    while (lo <= hi) {
        var mid = (lo + hi) >> 1;
        if (L.lines[mid].t <= positionMs) { idx = mid; lo = mid + 1; }
        else hi = mid - 1;
    }
    if (idx === L.index) return;
    L.index = idx;

    function at(i) { return (i >= 0 && i < L.lines.length) ? L.lines[i].text : ''; }
    L.text0 = at(idx - 1);
    L.text1 = at(idx);
    L.text2 = at(idx + 1);
    L.text3 = at(idx + 2);
}

/* ── 同步到 UI ────────────────────────────────────────────────── */
function medSyncToUi() {
    if (typeof UIA === 'undefined') return;

    var st = medPoll();

    UIA.data.mediaPlaying = st.playing;
    UIA.data.mediaTitle   = st.title || '';
    UIA.data.mediaArtist  = st.artist || '';
    UIA.data.songTitle    = st.title || '';
    UIA.data.songArtist   = st.artist || '';

    /* 歌词 */
    if (MEDIA.lyric.lines.length > 0) {
        medUpdateLyric(st.position);
        UIA.data.lyric0 = MEDIA.lyric.text0;
        UIA.data.lyric1 = MEDIA.lyric.text1;
        UIA.data.lyric2 = MEDIA.lyric.text2;
        UIA.data.lyric3 = MEDIA.lyric.text3;
    }
}

/** 调试状态 */
function medStatus() {
    return 'channel=' + MEDIA.channel
         + ' playing=' + MEDIA.state.playing
         + ' title=' + (MEDIA.state.title || '(空)')
         + ' lyricLines=' + MEDIA.lyric.lines.length;
}

/* ===== config.js ===== */
/**
 * config.js —— 配置化（布局 + 主题 + 开关）
 *
 * 设计目标：改配置不用改代码、不用重新打包。
 *
 * 配置来源优先级（后者覆盖前者）：
 *   ① 代码内置默认值（本文件 DEFAULTS）
 *   ② /data/local/tmp/mapcast.cfg     ← 主配置
 *   ③ /data/local/tmp/mapcast.local.cfg  ← 本机覆盖（可选）
 *
 * 文件格式（和 CP 侧 dashuid.cfg 一致，key=value 纯文本）：
 *
 *   # 主题
 *   theme.accent = #43E8C8
 *   theme.panel  = #A7000000
 *   theme.text   = #E6F1F1
 *
 *   # 布局（覆盖 UIL2 的默认值）
 *   gauge.x = 16
 *   gauge.y = 16
 *   gauge.w = 512
 *   gauge.h = 314
 *
 *   # 圆盘
 *   speedo.cx = 256
 *   speedo.r = 118
 *   speedo.max = 220
 *
 *   # 开关
 *   ui.enabled = true
 *   ui.scale = 1.0
 *
 * 为什么不用 JSON：
 *   车机上没有 JSON 解析器，而且手写 key=value 更容易让用户改。
 */

var CFGX = {
    inited: false,
    values: {},         /* key → 原始字符串 */
    applied: {},        /* 已应用到 UIL2/UIA 的项 */
    mtime: 0,
    err: ''
};

/* 内置默认值（UIL2 的镜像，作为兜底） */
var CFGX_DEFAULTS = {
    'theme.accent':     '#FF43E8C8',
    'theme.panel':      '#A7000000',
    'theme.panelEdge':  '#24FFFFFF',
    'theme.text':       '#FFE6F1F1',
    'theme.textDim':    '#FF75898E',
    'theme.evGreen':    '#FF5CE38A',
    'theme.fuelBlue':   '#FF4FC3FF',
    'theme.warn':       '#FFFFC93C',
    'theme.red':        '#FFFF5252',

    'gauge.x':  '16',  'gauge.y':  '16',
    'gauge.w':  '512', 'gauge.h':  '314',
    'ev.x':     '16',  'ev.y':     '342',
    'ev.w':     '512', 'ev.h':     '84',
    'fuel.x':   '16',  'fuel.y':   '438',
    'fuel.w':   '512', 'fuel.h':   '84',
    'clock.x':  '1424','clock.y':  '16',
    'clock.w':  '480', 'clock.h':  '92',
    'media.x':  '1424','media.y':  '120',
    'media.w':  '480', 'media.h':  '326',
    'gear.x':   '1424','gear.y':   '458',
    'gear.w':   '480', 'gear.h':   '64',
    'nav.x':    '544', 'nav.y':    '458',
    'nav.w':    '864', 'nav.h':    '64',
    'rail.x':   '544', 'rail.y':   '16',
    'rail.w':   '864', 'rail.h':   '40',
    'map.x':    '544', 'map.y':    '64',
    'map.w':    '864', 'map.h':    '382',

    'speedo.cx': '256', 'speedo.cy': '134', 'speedo.r': '118',
    'speedo.max': '220', 'speedo.over': '120',

    'ui.enabled': 'true',
    'ui.scale':   '1.0',
    'ui.fps':     '30',
    'ui.fpsIdle': '15'
};

var CFGX_PATHS = [
    '/data/local/tmp/mapcast.cfg',
    '/data/local/tmp/mapcast.local.cfg'
];

/* ── 解析 ─────────────────────────────────────────────────────── */

/** #RRGGBB 或 #AARRGGBB → 有符号 int32 */
function cfgxColor(hex, def) {
    if (!hex) return def;
    var s = String(hex).trim();
    if (s.charAt(0) === '#') s = s.substring(1);
    var v = 0;
    for (var i = 0; i < s.length; i++) {
        var c = s.charAt(i);
        var d = -1;
        if (c >= '0' && c <= '9') d = c.charCodeAt(0) - 48;
        else if (c >= 'a' && c <= 'f') d = c.charCodeAt(0) - 87;
        else if (c >= 'A' && c <= 'F') d = c.charCodeAt(0) - 55;
        else return def;
        v = v * 16 + d;
    }
    if (s.length <= 6) v = v + 0xFF000000 * 0x1000000;   /* 补 alpha */
    /* JS 的位运算天然是 int32，直接返回即可 */
    return v | 0;
}

/** 读一个配置文件，合并进 CFGX.values */
function cfgxLoadFile(path) {
    try {
        var File = Java.use('java.io.File');
        var f = File.$new(path);
        if (!f.exists()) return false;

        var FileInputStream = Java.use('java.io.FileInputStream');
        var BufferedReader = Java.use('java.io.BufferedReader');
        var InputStreamReader = Java.use('java.io.InputStreamReader');

        var fis = FileInputStream.$new(f);
        var isr = InputStreamReader.$new(fis, 'UTF-8');
        var br = BufferedReader.$new(isr);

        var line, count = 0;
        while ((line = br.readLine()) !== null) {
            var s = String(line).trim();
            if (!s || s.charAt(0) === '#') continue;
            var eq = s.indexOf('=');
            if (eq <= 0) continue;
            var k = s.substring(0, eq).trim();
            var v = s.substring(eq + 1).trim();
            if (!k) continue;
            CFGX.values[k] = v;
            count++;
        }
        br.close();
        log('CFGX: 加载 ' + path + ' (' + count + ' 项)');
        return true;
    } catch (e) {
        log('CFGX: 读取失败 ' + path + ' :: ' + String(e).substring(0, 60));
        return false;
    }
}

/* ── 应用 ─────────────────────────────────────────────────────── */

function cfgxGet(key) {
    return (CFGX.values[key] !== undefined) ? CFGX.values[key]
         : (CFGX_DEFAULTS[key] !== undefined ? CFGX_DEFAULTS[key] : null);
}
function cfgxNum(key, def) {
    var v = cfgxGet(key);
    if (v === null) return def;
    var n = parseFloat(v);
    return isNaN(n) ? def : n;
}
function cfgxBool(key, def) {
    var v = cfgxGet(key);
    if (v === null) return def;
    var s = String(v).toLowerCase();
    return (s === 'true' || s === '1' || s === 'yes' || s === 'on');
}

/** 把配置应用到 UIL2（布局）和 UIL2 颜色 */
function cfgxApply() {
    if (typeof UIL2 === 'undefined') return;

    /* 布局 */
    var rects = ['gauge', 'ev', 'fuel', 'clock', 'media', 'gear', 'nav', 'rail', 'map'];
    var attr = { gauge: 'gauge', ev: 'evCard', fuel: 'fuCard', clock: 'clock',
                 media: 'media', gear: 'gear', nav: 'nav', rail: 'rail', map: 'mapRect' };
    for (var i = 0; i < rects.length; i++) {
        var k = rects[i];
        var t = attr[k];
        if (!UIL2[t]) continue;
        UIL2[t].x = cfgxNum(k + '.x', UIL2[t].x);
        UIL2[t].y = cfgxNum(k + '.y', UIL2[t].y);
        UIL2[t].w = cfgxNum(k + '.w', UIL2[t].w);
        UIL2[t].h = cfgxNum(k + '.h', UIL2[t].h);
    }

    /* 圆盘 */
    UIL2.spdCx  = cfgxNum('speedo.cx',  UIL2.spdCx);
    UIL2.spdCy  = cfgxNum('speedo.cy',  UIL2.spdCy);
    UIL2.spdR   = cfgxNum('speedo.r',   UIL2.spdR);

    /* 颜色 */
    UIL2.cPanel     = cfgxColor(cfgxGet('theme.panel'),     UIL2.cPanel);
    UIL2.cPanelEdge = cfgxColor(cfgxGet('theme.panelEdge'), UIL2.cPanelEdge);
    UIL2.cTeal      = cfgxColor(cfgxGet('theme.accent'),    UIL2.cTeal);
    UIL2.cTxt       = cfgxColor(cfgxGet('theme.text'),      UIL2.cTxt);
    UIL2.cDim       = cfgxColor(cfgxGet('theme.textDim'),   UIL2.cDim);
    UIL2.cEvGreen   = cfgxColor(cfgxGet('theme.evGreen'),   UIL2.cEvGreen);
    UIL2.cFuelBlue  = cfgxColor(cfgxGet('theme.fuelBlue'),  UIL2.cFuelBlue);
    UIL2.cWarn      = cfgxColor(cfgxGet('theme.warn'),      UIL2.cWarn);
    UIL2.cRed       = cfgxColor(cfgxGet('theme.red'),       UIL2.cRed);

    /* 数据层：量程 / 阈值 */
    if (typeof UIA !== 'undefined' && UIA.data) {
        UIA.data.speedMax  = cfgxNum('speedo.max',  UIA.data.speedMax);
        UIA.data.speedOver = cfgxNum('speedo.over', UIA.data.speedOver);
    }

    /* 帧率 */
    if (typeof CFG !== 'undefined') {
        CFG.FPS      = cfgxNum('ui.fps',     CFG.FPS);
        CFG.IDLE_FPS = cfgxNum('ui.fpsIdle', CFG.IDLE_FPS);
    }

    log('CFGX: 已应用配置到 UIL2/UIA');
}

/* ── 初始化 ───────────────────────────────────────────────────── */
function cfgxInit() {
    if (CFGX.inited) return;
    CFGX.inited = true;

    /* 按顺序加载，后面覆盖前面 */
    for (var i = 0; i < CFGX_PATHS.length; i++) {
        cfgxLoadFile(CFGX_PATHS[i]);
    }

    cfgxApply();
    log('CFGX: 初始化完成，' + Object.keys(CFGX.values).length + ' 项来自文件');
}

/**
 * 热重载：检查配置文件有没有变，变了就重新应用。
 * 在 STAT 定时器里每秒调一次即可。
 */
function cfgxReloadIfChanged() {
    try {
        var File = Java.use('java.io.File');
        var changed = false;
        for (var i = 0; i < CFGX_PATHS.length; i++) {
            var f = File.$new(CFGX_PATHS[i]);
            if (!f.exists()) continue;
            var m = f.lastModified();
            if (m > CFGX.mtime) {
                CFGX.mtime = m;
                changed = true;
            }
        }
        if (changed) {
            CFGX.values = {};
            for (var j = 0; j < CFGX_PATHS.length; j++) {
                cfgxLoadFile(CFGX_PATHS[j]);
            }
            cfgxApply();
            log('CFGX: 配置已热重载');
        }
    } catch (e) {}
}

/* ===== map_to_dash_prod.js ===== */
/* ═══════════════════════════════════════════════════════════════════
 * map_to_dash_prod.js —— 地图渲染到仪表盘（生产版 v3）
 * ═══════════════════════════════════════════════════════════════════
 *
 * 目标：仪表盘显示地图，不碰 HUD（Display 1 / com.ecarx.hud）
 *
 * ── v3 关键优化（对比 v2）─────────────────────────────────────
 *
 * v2 问题：抓帧只有 2 次（12 fps 目标，实际 0.2/s）
 * 根因：PixelCopy 回调里用 JS 逐像素循环转 ARGB→BGRA
 *       W*H = 384,000 次迭代 ≈ 每帧阻塞数百毫秒
 *       → hh.postDelayed(下一帧) 被排队堵死
 *
 * v3 修法：Bitmap.ARGB_8888 在 Android 内存里就是 BGRA 布局（小端）
 *       用 copyPixelsToBuffer(ByteBuffer) 一次性拷出 = 原生内存拷贝
 *       不需要任何 JS 循环
 *
 * ── 已验证的技术参数 ──────────────────────────────────────────
 *   VirtualDisplay flags  = 0x3  (PUBLIC | PRESENTATION)
 *   Window type           = 2038 (TYPE_APPLICATION_OVERLAY)
 *   HUD 槽位               = 5    (SURFACE_VIEW_ID_HUD)
 *   DCBG  (切 VD 模式)     = 小端 44 43 42 47
 *   VDF1  (帧头 magic)     = 小端 31 46 44 56
 *   帧包 = magic+w+h+stride_px + BGRA(stride_px*h*4)，全小端
 *
 * ── 线程模型 ──────────────────────────────────────────────────
 *   Android 主线程 : MapView / PixelCopy / copyPixelsToBuffer
 *   Frida JS 线程  : socket 写入（避开 NetworkOnMainThread）
 */

var CFG = {
    W: 1920, H: 532,
    /*
     * FPS = 30（2026-11-08 从 12 提到 30）
     *
     * 实测对照（同一台车，dcapd2 maxFps=30）：
     *   FPS=12 → 抓 11.0fps，发 11.0fps，dcapd2 capture=11/秒
     *   FPS=30 → 抓 24.8fps，发 19.8fps，dcapd2 capture=19~21/秒
     *   收益 +82%，地图侧 CPU 仍 23%（不变），按 2ms 一帧算每帧只占 6% 核心
     *
     * 上限在哪：不是引擎，是 dcapd2 的 VD 分支
     *   `poll(&pf, 1, g_proxy_active ? 20 : 0)` + 每轮只读一帧 → 实际 20fps。
     *   再往上要改 dcapd2.c（一轮多读几帧），光调 FPS 或 maxFps 都没用：
     *   实测 maxFps=60 时 capture 仍是 20，只把 dcapd2 CPU 从 23% 拉到 75%。
     */
    FPS: 30,
    /*
     * 动态帧率（2026-11-08）
     *
     * 实测 CPU：30fps 固定 → 地图 25% + dcapd2 30% = 55%
     *           15fps 静止 → 约 40%
     * 这台车机本身负载就高（loadavg 5.2 / 6 核，内存只剩 935MB），
     * 所以导航和不导航分档跑：
     *   画面在动（导航中，路线/车标/车道箭头一直变）→ FPS(30)
     *   画面静止超过 IDLE_MS（停车、没路线）      → IDLE_FPS(15)
     *
     * 判据不依赖任何私有导航 API，直接采样 16 个像素点比对前后帧 —— 
     * 试过 GuidanceManager / RouteManager / GuideService 找 isNaving，
     * 都没暴露可用的状态方法，而且那些类初始化很重（探一次就超时）。
     */
    IDLE_FPS: 15,
    /* 多久没动就降档：3 秒。太短会在低速转弯时来回切，太长了省不下电 */
    IDLE_MS: 3000,
    /*
     * UI 叠加开关（2026-11-09）
     *
     * true  → 在地图上叠时间/车速/电池/油量/音乐卡片
     * false → 纯地图
     * 改这里不用动其它代码；ui_layer.js 里的 UIL 常量控制布局。
     *
     * **当前置 false** —— UI 层实验还没完成。
     *
     * uiInit() / uiComposite() 定义在 ui_layer.js 里，而那个文件**不在构建链里**
     * （assets/mapcast.js 只拼 java-bridge + 本文件）。
     * 之前 assets 里那份能跑，是因为某次手工把 ui_layer 段落也拼了进去 ——
     * 那是未提交的本地状态，重新生成就会丢。
     *
     * 保持 true 而 ui_layer 段缺失的后果是致命的：
     *   内部类回调里那句 `if (CFG.UI_ENABLED) uiComposite(bmp);` 没有 try/catch，
     *   uiComposite 未定义 → 每帧抛 ReferenceError →
     *   后面的 bmp.copyPixelsToBuffer(bb) 永远执行不到 →
     *   帧数据一个字节都拷不出来 → 地图完全不出画面。
     *
     * 要启用 UI 层时：把 ui_layer.js 正式加进构建链
     * （按 bridge → ui_layer → 本文件 的顺序拼接），再打开这个开关。
     */
    UI_ENABLED: true,
    /* 帧协议版本（2026-11-10）
     *
     * 1 = VDF1：16 字节头，无 CRC（原版，dcapd2 现在只认这个）
     * 2 = VDF2：32 字节头 + CRC32 + seq + dropped 标志
     *
     * 默认 1。等 dcapd2 侧支持 VDF2 后再切 2。
     */
    PROTO_VER: 1,
    /* 发送节奏：50ms=20Hz。比 FPS 稍慢一点，让发送端自然丢弃重复帧，
     * 避免 JS 线程和主线程抢同一份 buffer。实测设 33ms 反而掉到 15fps。 */
    SEND_MS: 50,
    D2_HOST: '127.0.0.1',
    D2_PORT: 8450,
    HUD_SLOT: 5,
    WIN_TYPE: 2038,
    DISP_NAME: 'DashcastMap',
    RUN_SECONDS: 0          /* 0 = 一直跑 */
};

function log(s) { console.log('[MAP] ' + s); }

var NET = { sock: null, out: null, ok: false, err: 'none' };
var FP  = { seq: 0, arr: null, prod: 0, sent: 0, err: 0 };
var CTX = { stopper: false, hh: null, bmp: null, buf: null, mv: null, hc: null, listener: null,
            /* 必须持住这三个，否则 GC 回收 VirtualDisplay → -19 进程被杀 */
            vd: null, surf: null, st: null };
/* 双缓冲：两个 ByteBuffer 轮换，避免 JS 线程读到正在写的缓冲 */
var BUF = { b0: null, b1: null, wi: 0 };

/*
 * 动态帧率的采样点：4×4 网格，每格取中心
 * （BGRA 每像素 4 字节，所以下标 = (y*W + x) * 4）
 * 16 个点足够判断"地图在不在动"，成本 16 次数组访问 = 微秒级
 */
var SAMPLE_OFFS = (function () {
    var a = [];
    for (var gy = 0; gy < 4; gy++) {
        for (var gx = 0; gx < 4; gx++) {
            var x = Math.floor(CFG.W * (gx + 0.5) / 4);
            var y = Math.floor(CFG.H * (gy + 0.5) / 4);
            a.push((y * CFG.W + x) * 4);
        }
    }
    return a;
})();

/* 动态帧率状态 */
var DYN = {
    last: new Array(16).fill(-1),   /* 上一帧的 16 个采样值 */
    lastMoveAt: Date.now(),          /* 最后一次检测到"画面在动"的时刻 */
    fps: 30                          /* 当前生效帧率，初始按高帧率 */
};

/* ── 网络 ─────────────────────────────────────────────────── */
function d2_connect() {
    try {
        if (NET.sock !== null) { try { NET.sock.close(); } catch (e) {} }
        NET.sock = Java.use('java.net.Socket').$new(CFG.D2_HOST, CFG.D2_PORT);
        NET.sock.setTcpNoDelay(true);
        NET.out = NET.sock.getOutputStream();
        NET.out.write.overload('[B').call(NET.out,
            Java.array('byte', [0x44, 0x43, 0x42, 0x47]));   /* DCBG */
        NET.out.flush();
        NET.ok = true;
        NET.err = 'none';
        log('已连 dcapd2 并发送 DCBG（切 src=VD）');
        return true;
    } catch (e) {
        NET.ok = false;
        NET.err = String(e).substring(0, 70);
        log('连接失败: ' + NET.err);
        return false;
    }
}

/* ── Frida 线程发送 ──────────────────────────────────────── */
/*
 * 协议加固（2026-11-10，参考 GD-Bridge 的 GCP1 设计）
 *
 * 原始 VDF1：magic(4) + w(4) + h(4) + stride(4) = 16 字节头，无校验。
 *
 * 加固后（VDF2，向后兼容 VDF1）：
 *   offset 0   4   magic   0x56444632 ("VDF2")
 *   offset 4   4   w
 *   offset 8   4   h
 *   offset 12  4   stride_px
 *   offset 16  4   seq            帧序号
 *   offset 20  4   payloadCrc     CRC32(payload)
 *   offset 24  4   headerCrc      CRC32(前 24 字节)
 *   offset 28  4   flags          bit0=DROPPED（上一帧丢了）
 *   total 32 字节头 + payload
 *
 * 为什么加 CRC：
 *   车机网络不稳，一帧 4MB 传输中途出错，dcapd2 收到残缺数据会画出
 *   花屏甚至崩。CRC 让它能丢弃坏帧、保留上一帧。
 *
 * 为什么加 seq：
 *   dcapd2 可以检测丢帧（seq 不连续），配合看门狗判断链路死。
 *
 * 兼容性：
 *   dcapd2 如果只认 VDF1，会因为我们发 VDF2 而报错。
 *   所以用 CFG.PROTO_VER 控制，默认仍是 1（VDF1），
 *   等 dcapd2 侧改好了再切到 2。
 */
var lastSeq = 0;
var txSeq = 0;
var dropCount = 0;

/* CRC32 查表（懒初始化） */
var CRC_TABLE = null;
function crc32Init() {
    if (CRC_TABLE !== null) return;
    CRC_TABLE = [];
    for (var n = 0; n < 256; n++) {
        var c = n;
        for (var k = 0; k < 8; k++) {
            c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        }
        CRC_TABLE[n] = c >>> 0;
    }
}
function crc32(arr, len) {
    crc32Init();
    var c = 0xFFFFFFFF;
    for (var i = 0; i < len; i++) {
        c = CRC_TABLE[(c ^ arr[i]) & 0xFF] ^ (c >>> 8);
    }
    return (c ^ 0xFFFFFFFF) >>> 0;
}
/* 头部字节算 CRC（头部是小字节数组） */
function crc32Bytes(bytes, len) {
    crc32Init();
    var c = 0xFFFFFFFF;
    for (var i = 0; i < len; i++) {
        c = CRC_TABLE[(c ^ (bytes[i] & 0xFF)) & 0xFF] ^ (c >>> 8);
    }
    return (c ^ 0xFFFFFFFF) >>> 0;
}
function le32(v) {
    return [v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF, (v >>> 24) & 0xFF];
}

setInterval(function () {
    if (FP.seq === lastSeq || FP.arr === null) return;
    if (!NET.ok) { d2_connect(); return; }

    var arr = FP.arr;
    var dropped = (FP.seq - lastSeq) > 1;   /* 中间跳过了帧 */
    if (dropped) dropCount++;
    lastSeq = FP.seq;
    txSeq++;

    try {
        var hdr;
        if (CFG.PROTO_VER === 2) {
            /* VDF2：32 字节头 + CRC */
            var h = [];
            /* magic "VDF2" */
            h = h.concat([0x32, 0x46, 0x44, 0x56]);
            h = h.concat(le32(CFG.W));
            h = h.concat(le32(CFG.H));
            h = h.concat(le32(CFG.W));            /* stride_px */
            h = h.concat(le32(txSeq));
            var payCrc = crc32(arr, FRAME_BYTES);
            h = h.concat(le32(payCrc));
            var hdrCrc = crc32Bytes(h, 24);
            h = h.concat(le32(hdrCrc));
            h = h.concat(le32(dropped ? 1 : 0));
            hdr = Java.array('byte', h);
        } else {
            /* VDF1：16 字节头（原样，无校验） */
            hdr = Java.array('byte', [
                0x31, 0x46, 0x44, 0x56,
                (CFG.W & 0xFF), ((CFG.W >> 8) & 0xFF), 0, 0,
                (CFG.H & 0xFF), ((CFG.H >> 8) & 0xFF), 0, 0,
                (CFG.W & 0xFF), ((CFG.W >> 8) & 0xFF), 0, 0
            ]);
        }
        NET.out.write.overload('[B').call(NET.out, hdr);
        NET.out.write.overload('[B').call(NET.out, arr);
        NET.out.flush();
        FP.sent++;
        FP.lastTxAt = Date.now();
    } catch (e) {
        NET.ok = false;
        NET.err = String(e).substring(0, 70);
        log('发送失败，将重连: ' + NET.err);
    }
}, CFG.SEND_MS);

/* ── 看门狗（2026-11-10）─────────────────────────────────────
 *
 * 每 2 秒检查一次：
 *   · 超过 3 秒没成功发帧 → 判定链路死，重连
 *   · 连续重连失败 5 次 → 打日志（dcapd2 可能没启动）
 *
 * 为什么需要：
 *   dcapd2 崩溃后 TCP 连接会先僵死（不发 FIN），
 *   我们继续 write 也不会报错，但帧全丢。
 *   靠"上次成功发送时间"能发现这种情况。
 */
setInterval(function () {
    if (!NET.ok) return;
    var now = Date.now();
    var lastOk = FP.lastTxAt || 0;
    if (lastOk > 0 && (now - lastOk) > 3000) {
        log('看门狗：3 秒无成功发送，强制重连');
        NET.ok = false;
        d2_connect();
    }
}, 2000);

/* ── 统计 ─────────────────────────────────────────────────── */
var tPrev = Date.now(), pPrev = 0, sPrev = 0;
setInterval(function () {
    var now = Date.now(), dt = (now - tPrev) / 1000;
    if (dt <= 0) return;
    var pf = (FP.prod - pPrev) / dt, sf = (FP.sent - sPrev) / dt;
    tPrev = now; pPrev = FP.prod; sPrev = FP.sent;

    /* 车辆数据同步（5 秒一次，和 STAT 同频）
     *
     * 数据变化慢，不需要每帧读。读失败沿用上次值，不会跳 0。
     */
    /* 配置热重载（改 /data/local/tmp/mapcast.cfg 后 5 秒内生效） */
    try {
        if (typeof cfgxReloadIfChanged === 'function') cfgxReloadIfChanged();
    } catch (e) {}

    var vInfo = '';
    try {
        if (typeof dashSyncVehicle === 'function' && typeof DASH !== 'undefined' && DASH.hud) {
            dashSyncVehicle();
            vInfo = ' 车辆=' + (typeof VEH !== 'undefined' ? VEH.channel : '?')
                  + '(' + Math.round(DASH.hud.data.speed) + 'km/h)';
        }
    } catch (e) {}

    var mInfo = '';
    try {
        if (typeof dashSyncMedia === 'function' && typeof DASH !== 'undefined' && DASH.hud) {
            dashSyncMedia();
            mInfo = ' 媒体=' + (MEDIA.state.title ? '(' + MEDIA.state.title + ')' : '(空)');
        }
    } catch (e) {}

    /* mock 数据：没接真实车辆时让车速自己动，验证 UI */
    try {
        if (typeof dashMockTick === 'function' && typeof DASH_MOCK !== 'undefined' && DASH_MOCK.on) {
            dashMockTick();
        }
    } catch (e) {}

    log('STAT 抓=' + FP.prod + '(' + pf.toFixed(1) + 'fps)'
        + ' 发=' + FP.sent + '(' + sf.toFixed(1) + 'fps)'
        + ' 耗时=' + (FP.cost || 0) + 'ms 错=' + FP.err
        + ' [UI=' + (FP.tUi || 0) + ' 拷贝=' + (FP.tCopy || 0) + ' 取=' + (FP.tGet || 0)
        + ' draw=' + (typeof DASH !== 'undefined' ? (DASH.lastDrawMs || 0) : 0) + ']'
        + ' 帧率=' + DYN.fps + (DYN.fps === CFG.IDLE_FPS ? '(静止)' : '(活动)')
        + vInfo + mInfo
        + ' 网=' + (NET.ok ? 'OK' : 'DOWN'));
}, 5000);

/* ── 主流程 ──────────────────────────────────────────────── */
Java.perform(function () {
    log('start, pid=' + Process.id);

    var app = Java.use('android.app.ActivityThread').currentApplication();
    var Context = Java.use('android.content.Context');
    var Looper = Java.use('android.os.Looper');
    var Handler = Java.use('android.os.Handler');
    var W = CFG.W, H = CFG.H;
    var FRAME_BYTES = W * H * 4;

    if (!d2_connect()) log('dcapd2 未就绪，会自动重试');

    /* ① VirtualDisplay（PRESENTATION 标志是 Presentation 能工作的前提） */
    var SurfaceTexture = Java.use('android.graphics.SurfaceTexture');
    var Surface = Java.use('android.view.Surface');
    var st = SurfaceTexture.$new(0);
    st.setDefaultBufferSize(W, H);
    var surf = Surface.$new(st);

    var dm = app.getSystemService(Context.DISPLAY_SERVICE.value);
    var CD = Java.use('android.hardware.display.DisplayManager');
    var cdOv = null;
    CD.createVirtualDisplay.overloads.forEach(function (m) {
        if (m.argumentTypes.length === 6) cdOv = m;
    });
    var vd = cdOv.call(dm, CFG.DISP_NAME, W, H, 160, surf, 0x3);
    var displayId = vd.getDisplay().getDisplayId();
    log('VirtualDisplay displayId=' + displayId + ' flags=0x3');

    /* ★★ 必须持住 vd / surf / st（2026-11-10，-19 崩溃的根因）
     *
     * 症状：
     *   logcat 里刷满
     *     CompositionEngine: ANativeWindow::dequeueBuffer failed
     *     for display [DashcastMap] with error: -19
     *   然后 Process terminated —— 地图进程被系统杀掉。
     *
     * 原因：
     *   这三个对象原来只是 Java.perform 回调里的局部变量。
     *   回调返回后没人引用，ART 的 GC 就把 VirtualDisplay 回收了。
     *   VirtualDisplay 一死，它内部持有的 producer Surface 变成
     *   ENODEV(-19)，SurfaceFlinger 还在往这块已释放的 buffer 里合成
     *   → dequeueBuffer 疯狂失败 → 进程被干掉。
     *
     * 修：挂到 CTX 上，生命周期跟着整个注入会话。
     */
    CTX.vd = vd;
    CTX.surf = surf;
    CTX.st = st;

    CTX.hh = Handler.$new(Looper.getMainLooper());
    var R = Java.use('java.lang.Runnable');
    var ByteBuffer = Java.use('java.nio.ByteBuffer');

    var startPc = function () {
                try {
                    var Bitmap = Java.use('android.graphics.Bitmap');
                    var Config = Java.use('android.graphics.Bitmap$Config');
                    var bmp = Bitmap.createBitmap(W, H, Config.ARGB_8888.value);
                    CTX.bmp = bmp;

                    /* UI 层：bmp 建好后初始化。它自己维护一张独立的 UI Bitmap，
                     * 每帧合成时才贴到 bmp 上（见 uiComposite 调用处）
                     *
                     * 注意函数名：Android Canvas 版是 uiaInit()，
                     * 不是旧的 uiInit()（那是 CP 侧 mcv 方案的名字）。 */
                    if (CFG.UI_ENABLED) {
                        /* 先加载配置（可能覆盖 UIL2 的布局和颜色），
                         * 再初始化 UI —— 顺序不能反。
                         *
                         * cfgxInit() 读 /data/local/tmp/mapcast.cfg，
                         * 里面有 gauge.x / speedo.r / theme.accent 这些，
                         * 必须在 uiaInit() 建画布之前应用。 */
                        try {
                            if (typeof cfgxInit === 'function') cfgxInit();
                        } catch (e) {
                            log('配置加载失败 ' + String(e).substring(0, 80));
                        }

                        /* 用 dashboard.js + Canvas2D 适配器（2026-11-10 换方案）
                         *
                         * 不再用旧的 ui_android.js（手写简化版），
                         * 改成跑用户的 dashboard.js —— 设计更完整，
                         * 2171 行、14 个 draw* 模块、中文原生支持。
                         */
                        try {
                            if (typeof dashInit === 'function') {
                                dashInit();
                                if (typeof dashCreateHud === 'function') dashCreateHud();
                            }
                        } catch (e) {
                            log('仪表盘初始化失败 ' + String(e).substring(0, 80));
                        }

                        /* ★ 车辆数据：五路并联探测（vehicle.js）
                         *
                         * 探测一次，之后每秒同步一次（数据变化慢，不需要每帧读）。
                         * 探测失败会降级 mock，不影响投屏。
                         */
                        try {
                            if (typeof vehInit === 'function') {
                                var ch = vehInit();
                                log('车辆数据通道: ' + ch);
                                if (typeof vehSyncToUi === 'function') vehSyncToUi();
                            }
                        } catch (e) {
                            log('车辆数据初始化失败 ' + String(e).substring(0, 80));
                        }

                        /* ★ 媒体信息（media.js）
                         *
                         * 大概率拿不到（地图进程没有 MEDIA_CONTENT_CONTROL），
                         * 失败就显示"暂无媒体"，不影响其他功能。
                         */
                        try {
                            if (typeof medInit === 'function') {
                                var mch = medInit();
                                log('媒体通道: ' + mch);
                            }
                        } catch (e) {
                            log('媒体初始化失败 ' + String(e).substring(0, 80));
                        }
                    }
                    /* ★ 红蓝互换用的目标 Bitmap + Canvas（Java 层原生速度） */
                    var swapBmp = Bitmap.createBitmap(W, H, Config.ARGB_8888.value);
                    var CanvasC = Java.use('android.graphics.Canvas');
                    var swapCanvas = CanvasC.$new(swapBmp);
                    var CMC = Java.use('android.graphics.ColorMatrix');
                    var cmArr = Java.array('float', [0,0,1,0,0,  0,1,0,0,0,  1,0,0,0,0,  0,0,0,1,0]);
                    var cm = CMC.$new(cmArr);
                    var CPC = Java.use('android.graphics.ColorMatrixColorFilter');
                    var cf = CPC.$new(cm);
                    var PC2 = Java.use('android.graphics.Paint');
                    var paint = PC2.$new();
                    paint.setColorFilter(cf);

/* ★ 字节序说明（实测）:
 *   Bitmap.copyPixelsToBuffer(ARGB_8888) → 内存是 R,G,B,A 序
 *   dcapd2 的 blit_bilinear 期待 B,G,R,A 序
 *   直接发会导致红蓝互换（绿通道不受影响）
 *
 *   本轮先按原样发送，验证通道吞吐；颜色修正留待后续（可用 ColorMatrix 或
 *   dcapd2 侧加标志位）。
 */
                    var BO = Java.use('java.nio.ByteOrder');
                    BUF.b0 = ByteBuffer.allocateDirect(FRAME_BYTES);
                    BUF.b1 = ByteBuffer.allocateDirect(FRAME_BYTES);
                    /* ★ 预分配 byte[] 复用，消除每帧 1.5MB JS 数组创建 */
                    BUF.a0 = Java.array('byte', new Array(FRAME_BYTES).fill(0));
                    BUF.a1 = Java.array('byte', new Array(FRAME_BYTES).fill(0));

                    var PC = Java.use('android.view.PixelCopy');
                    var PCL = Java.use('android.view.PixelCopy$OnPixelCopyFinishedListener');
                    var hh = CTX.hh;

                    CTX.listener = Java.registerClass({
                        name: 'com.dashcast.MapProd3PC' + Process.id,
                        implements: [PCL],
                        methods: {
                            onPixelCopyFinished: function (res) {
                                if (CTX.stopper) return;

                                    if (res === 0) {
                                        try {
                                        /* 双缓冲：idx 同步切换，bb 与 arr 配对使用 */
                                        var t0 = Date.now();
                                        var tUi = 0, tCopy = 0, tGet = 0;
                                        var idx = BUF.wi;
                                        BUF.wi ^= 1;

                                        var bb = (idx === 0) ? BUF.b0 : BUF.b1;
                                        var arr = (idx === 0) ? BUF.a0 : BUF.a1;

                                    /* ★ UI 合成（必须在 copyPixelsToBuffer 之前）
                                     *
                                     * PixelCopy 每帧把地图覆盖写进 bmp，
                                     * 所以每帧都要重新把 dashboard 画上去。
                                     *
                                     * dashFrame() 内部：
                                     *   · 累加 dashboard 的动画时间
                                     *   · 调 hud.draw(t)（14 个 draw* 模块）
                                     *   · Canvas2D 适配器把绘制落到 DASH.bmp 上
                                     *
                                     * 然后把 DASH.bmp 贴到地图 bmp 上（一次 drawBitmap）。
                                     */
                                    if (CFG.UI_ENABLED) {
                                        try {
                                            var tUi0 = Date.now();
                                            if (typeof dashFrame === 'function') {
                                                dashFrame();
                                                if (typeof DASH !== 'undefined' && DASH.bmp) {
                                                    if (CTX.uiCanvas === null || CTX.uiCanvas === undefined) {
                                                        CTX.uiCanvas = Java.use('android.graphics.Canvas').$new(bmp);
                                                    }
                                                    CTX.uiCanvas.drawBitmap(DASH.bmp, 0, 0, CTX.uiPaint || (CTX.uiPaint = (function(){
                                                        var pp = Java.use('android.graphics.Paint').$new();
                                                        pp.setAntiAlias(false);
                                                        pp.setFilterBitmap(false);
                                                        pp.setAlpha(255);
                                                        return pp;
                                                    })()));
                                                }
                                            }
                                            tUi = Date.now() - tUi0;
                                        } catch (e) {
                                            if (!CTX.uiErrShown) {
                                                CTX.uiErrShown = true;
                                                log('UI 合成失败 ' + String(e).substring(0, 100));
                                            }
                                        }
                                    }

                                        /* ★ 直接拷贝（copyPixelsToBuffer 出的就是
                                           dcapd2 要的字节序，无需 ColorMatrix 转换）
                                           实测：加了 R/B 互换反而让车标由蓝变橙 */
                                        var tCopy0 = Date.now();
                                        bb.rewind();
                                        bmp.copyPixelsToBuffer(bb);
                                        tCopy = Date.now() - tCopy0;

                                        /* ★ 复用预分配 byte[]，无 JS 数组创建 */
                                        var tGet0 = Date.now();
                                        bb.rewind();
                                        bb.get(arr, 0, FRAME_BYTES);
                                        tGet = Date.now() - tGet0;

                                        FP.arr = arr;
                                        FP.seq++;
                                        FP.prod++;
                                        FP.cost = Date.now() - t0;
                                        /* 分段耗时：UI 渲染 / copyPixelsToBuffer / bb.get */
                                        FP.tUi = tUi; FP.tCopy = tCopy; FP.tGet = tGet;

                                        /* ── 画面变化检测（动态帧率用） ──
                                         *
                                         * 采样 16 个点（4×4 网格，各取中心），逐帧比对。
                                         * 变化点数 > 阈值就用高帧率，否则降档。
                                         *
                                         * 为什么采样而不是全图比对：全图 1920×532×4 = 4MB，
                                         * 每帧扫一遍在 JS 里会拖垮主线程（实测能到 100ms+）。
                                         * 16 个点足够判断"地图有没有在动" —— 导航中路线、
                                         * 车标、车道箭头一直在动；停车时几乎全静止。
                                         */
                                        try {
                                            var changed = 0;
                                            for (var si = 0; si < SAMPLE_OFFS.length; si++) {
                                                var off = SAMPLE_OFFS[si];
                                                var cur = arr[off] | (arr[off + 1] << 8) | (arr[off + 2] << 16);
                                                if (cur !== DYN.last[si]) {
                                                    changed++;
                                                    DYN.last[si] = cur;
                                                }
                                            }
                                            var now2 = Date.now();
                                            if (changed >= SAMPLE_OFFS.length / 4) {
                                                /* 4 个点以上变了 → 认为在动，立刻升档 */
                                                DYN.lastMoveAt = now2;
                                            }
                                            /* 超过 3 秒没动 → 降档 */
                                            var moving = (now2 - DYN.lastMoveAt) < CFG.IDLE_MS;
                                            var targetFps = moving ? CFG.FPS : CFG.IDLE_FPS;
                                            if (targetFps !== DYN.fps) {
                                                DYN.fps = targetFps;
                                                log('帧率切换 ' + targetFps + 'fps（'
                                                    + (moving ? '画面在动' : '画面静止')
                                                    + '，变化点 ' + changed + '/' + SAMPLE_OFFS.length + '）');
                                            }
                                        } catch (e) {}
                                    } catch (e) {
                                        FP.err++;
                                        if (FP.err <= 3) log('拷贝失败 ' + String(e).substring(0, 60));
                                    }
                                } else {
                                    FP.err++;
                                    if (FP.err <= 3) log('PC 失败 code=' + res);
                                }

                                /* 自链：trigInst 是闭包变量。
                                 * 间隔用 DYN.fps（动态帧率），不是固定的 CFG.FPS */
                                if (!CTX.stopper) {
                                    hh.postDelayed(trigInst, Math.round(1000 / DYN.fps));
                                }
                            }
                        }
                    }).$new();

                    var TrigCls = Java.registerClass({
                        name: 'com.dashcast.MapProd3T' + Process.id,
                        implements: [R],
                        methods: {
                            run: function () {
                                if (CTX.stopper) return;
                                try {
                                    /* ★ 抓 HudActivity 自己的 SurfaceView
                                     *
                                     * 原来是 mv.getHolder().getSurface()，mv 是我们手动
                                     * 创建的 basemap.MapView —— 那条路引擎不给导航图层。
                                     * 现在抓 HudActivity 内建的 SurfaceView，
                                     * 它背后是完整的 GuidanceLayer。
                                     *
                                     * 优先用 Surface 重载（不含盖在上面的 View），
                                     * 拿不到 Surface 再退回 SurfaceView 重载。
                                     */
                                    var surface = null;
                                    try {
                                        surface = CTX.sv.getHolder().getSurface();
                                    } catch (e) {}

                                    if (surface && surface.isValid()) {
                                        PC.request.overload(
                                            'android.view.Surface',
                                            'android.graphics.Bitmap',
                                            'android.view.PixelCopy$OnPixelCopyFinishedListener',
                                            'android.os.Handler'
                                        ).call(PC, surface, bmp, CTX.listener, hh);
                                    } else {
                                        PC.request.overload(
                                            'android.view.SurfaceView',
                                            'android.graphics.Bitmap',
                                            'android.view.PixelCopy$OnPixelCopyFinishedListener',
                                            'android.os.Handler'
                                        ).call(PC, CTX.sv, bmp, CTX.listener, hh);
                                    }
                                } catch (e) {
                                    FP.err++;
                                    if (FP.err <= 3) log('request 失败 ' + String(e).substring(0, 60));
                                    hh.postDelayed(trigInst, 200);
                                }
                            }
                        }
                    });
                    var trigInst = TrigCls.$new();

                    hh.postDelayed(trigInst, 1200);
                    log('PixelCopy 链已启动（目标 ' + CFG.FPS + 'fps）');

                    if (CFG.RUN_SECONDS > 0) {
                        var StopR = Java.registerClass({
                            name: 'com.dashcast.MapProd3Stop' + Process.id,
                            implements: [R],
                            methods: {
                                run: function () {
                                    CTX.stopper = true;
                                    log('自动停止 抓=' + FP.prod + ' 发=' + FP.sent);
                                }
                            }
                        });
                        hh.postDelayed(StopR.$new(), CFG.RUN_SECONDS * 1000);
                    }

                } catch (e) {
                    log('ERR ' + String(e).substring(0, 130));
                }
    };
    var Main = Java.registerClass({
        name: 'com.dashcast.MapProd3' + Process.id,
        implements: [R],
        methods: {
            run: function () {
                try {
                    /*
                     * ② 启动 HudActivity 到我们的 VirtualDisplay
                     *
                     * **为什么不用手动 HUDControl.initSurfaceView**（2026-11-08）
                     *
                     * 之前的做法是自己 new HUDControl() + initSurfaceView(MapView)，
                     * 引擎只给了底图（MapLayer），不给 GuidanceLayer ——
                     * 结果仪表盘上没有导航路线、没有红绿灯、没有车道指引。
                     *
                     * 实测原因：引擎把「路线/红绿灯/车道」这些 native 层
                     * （RoutePathLayer / 对应 BizControl）只在 Activity 生命周期内
                     * 创建。手动挂载的 MapView 拿不到。
                     *
                     * HudActivity 是地图 App 内置的 HUD 投屏 Activity
                     * （com.ecarx.navi.multiscreen.hud.HudActivity，exported=true,
                     * launchMode=singleTask）。把它启动到我们的 VD 上，引擎会：
                     *   ① 自动建好 SurfaceView (1920x532)
                     *   ② 创建 HUDControl（surfaceViewID=5, mScreenWidth/Height 正确）
                     *   ③ 完整创建 GuidanceLayer(slot5) + 9 个 Control
                     *   ④ initMapParam(ExternalMapParam{isTmcVisible=true, ...})
                     *
                     * 我们随后只做一件事：从 HUDControl 的 surfaceViewControl
                     * 里取出 SurfaceView，对它 PixelCopy 抓帧。
                     */
                    /*
                     * ③ 发起 HudActivity
                     *
                     * **实测教训（2026-11-08）**：
                     *   从 App 上下文 startActivity 到「别人创建的 VirtualDisplay」会抛
                     *     SecurityException: Permission Denial: starting Intent {...}
                     *   跨 display 启动需要 INTERNAL_SYSTEM_WINDOW 级权限，普通 App 拿不到。
                     *
                     *   而 `adb shell am start --display N -n ...` 用 shell 身份能成功
                     *   （已实测：dumpsys 显示 Activity Resumed 在 display 35）。
                     *
                     *   所以策略：这里把 displayId 打到日志（VDID=<n>），
                     *   由 Dashcast 侧读日志后调 adb shell 发起。
                     *
                     *   **这里必须 try/catch**：异常漏到外层会让整段 Main 中断，
                     *   轮询和 PixelCopy 都不会启动（第一次改就是这样，0.0fps）。
                     */
                    log('VDID=' + displayId);
                    try {
                        var Intent = Java.use('android.content.Intent');
                        var CN = Java.use('android.content.ComponentName');
                        var AO = Java.use('android.app.ActivityOptions');
                        var it = Intent.$new();
                        it.setComponent(CN.$new('com.geely.map',
                            'com.ecarx.navi.multiscreen.hud.HudActivity'));
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.value);
                        var opts = AO.makeBasic();
                        opts.setLaunchDisplayId(displayId);
                        app.startActivity(it, opts.toBundle());
                        log('HudActivity 已在进程内发起（displayId=' + displayId + '）');
                    } catch (eSelf) {
                        log('进程内启动被拒（预期，等 adb shell 发起）');
                    }

                    /* 必须在主线程轮询：Activity 启动是异步的，onCreate 要几秒 */
                    var want = 20;      /* 最多轮询 20 次 x 1.5s = 30s */
                    var tries = 0;

                    var Poll = Java.registerClass({
                        name: 'com.dashcast.Poll' + Process.id,
                        implements: [R],
                        methods: {
                            run: function () {
                                if (CTX.stopper) return;
                                tries++;
                                try {
                                    var found = null;
                                    var seen = [];
                                    Java.choose('com.ecarx.navi.multiscreen.hud.HudActivity', {
                                        onMatch: function (a) {
                                            try {
                                                if (a.isDestroyed()) return;
                                                var did = -1;
                                                try { did = a.getDisplay().getDisplayId(); } catch (eD) {}
                                                seen.push(did);
                                                /* ★ 放宽匹配（2026-11-10）
                                                 *
                                                 * 原来要求 did === displayId（我们的 VD）。
                                                 * 但 HudActivity 通过 `am display move-stack`
                                                 * 搬过来之后，个别时机下 getDisplayId()
                                                 * 还是旧值，或 Activity 尚未 finish 迁移，
                                                 * 于是永远匹配不上 —— 日志一直
                                                 * 「HudActivity 未就绪」，STAT 恒 0fps，
                                                 * 而 dumpsys 里它明明已经 Resumed 在目标屏。
                                                 *
                                                 * 改成：优先要目标 display；没有就先收下第一个
                                                 * 存活实例（后面 startPc 会拿 SurfaceView，
                                                 * 真实尺寸还是由 HUDControl 决定）。
                                                 */
                                                if (!found) {
                                                    found = a;
                                                } else if (did === displayId) {
                                                    found = a;   /* 命中目标屏，覆盖 */
                                                }
                                            } catch (e) {}
                                        },
                                        onComplete: function () {}
                                    });
                                    if (tries <= 3) {
                                        log('轮询 ' + tries + ' :: 找到 ' + seen.length
                                            + ' 个实例 display=' + seen.join(',')
                                            + ' 目标=' + displayId);
                                    }

                                    if (found) {
                                        /*
                                         * **全部私有字段都要用反射**（2026-11-08，两次踩坑）
                                         *
                                         * 这条链上有三个 private 字段：
                                         *   HudActivity.service
                                         *   HUDService.mControl
                                         *   HUDControl.surfaceViewControl
                                         *   SurfaceViewControl.mSurfaceView
                                         *
                                         * frida 的 `obj.field.value` 只对 **public** 字段有效；
                                         * private 字段读出来是 undefined，再取 .value 就抛
                                         *   TypeError: Cannot read properties of undefined
                                         *
                                         * 第一次只改了最后一个，仍然在 `found.service.value`
                                         * 这一步失败 —— 日志里的 "轮询 2/3 :: TypeError" 就是这个。
                                         * 统一写个 rf() 帮手，四个字段全走 getDeclaredField。
                                         */
                                        var rf = function (obj, fname) {
                                            try {
                                                var f = obj.getClass()
                                                    .getDeclaredField(fname);
                                                f.setAccessible(true);
                                                return f.get(obj);
                                            } catch (e) {
                                                return null;
                                            }
                                        };

                                        /* ★ 字段没就绪时必须"重新调度"，不能 return
                                         *
                                         * bug（2026-11-10 实测）：
                                         *   原来这里写 `if (!svc) { return; }`
                                         *   —— return 直接退出 run()，
                                         *   跳过了末尾的 `if (tries < want) postDelayed`，
                                         *   于是轮询只跑 1 次就死掉。
                                         *
                                         * 现象：HudActivity 明明已在目标 display 上
                                         * （dumpsys 可见 Resumed + 有 Window），
                                         * 日志却始终打「HudActivity 未就绪」，
                                         * STAT 恒 0fps。
                                         *
                                         * 修：抽成 rePoll()，字段缺失时先重排再 return。
                                         */
                                        var rePoll = function (why) {
                                            if (tries <= 3) log('轮询 ' + tries + ' :: 未就绪(' + why + ')');
                                            if (tries < want) {
                                                hh.postDelayed(pollInst, 1500);
                                            } else {
                                                log('HudActivity 未就绪（轮询 ' + tries + ' 次后放弃）');
                                            }
                                        };

                                        var svc = rf(found, 'service');
                                        if (!svc) { rePoll('service'); return; }
                                        var hc = rf(svc, 'mControl');
                                        if (!hc) { rePoll('mControl'); return; }
                                        var sfc = rf(hc, 'surfaceViewControl');
                                        if (!sfc) { rePoll('surfaceViewControl'); return; }
                                        var msv = rf(sfc, 'mSurfaceView');
                                        if (!msv) { rePoll('mSurfaceView'); return; }
                                        if (msv) {
                                            CTX.hudAct = found;
                                            CTX.hc = hc;
                                            CTX.sv = msv;
                                            log('HudActivity 就绪：surfaceViewID='
                                                + rf(hc, 'surfaceViewID')
                                                + '  ' + rf(hc, 'mScreenWidth') + 'x'
                                                + rf(hc, 'mScreenHeight'));
                                            log('SurfaceView = ' + msv);

                                            /*
                                             * ★ 先把 HUDControl 的屏幕尺寸改对（2026-11-08）
                                             *
                                             * 初值是 2560×1440 —— HudActivity 启动时读的是
                                             * **主屏** DisplayMetrics，不是我们 VD 的 1920×532。
                                             * 两个字段都是 private int，走反射 setInt。
                                             */
                                            try {
                                                var fw = hc.getClass()
                                                    .getDeclaredField('mScreenWidth');
                                                fw.setAccessible(true);
                                                fw.setInt(hc, W);
                                                var fhgt = hc.getClass()
                                                    .getDeclaredField('mScreenHeight');
                                                fhgt.setAccessible(true);
                                                fhgt.setInt(hc, H);
                                                log('HUDControl 尺寸 → ' + W + 'x' + H);
                                            } catch (eS) {
                                                log('尺寸修正失败: '
                                                    + String(eS).substring(0, 60));
                                            }

                                            /*
                                             * ★ 修正 MapView 的 viewport（2026-11-08 实测）
                                             *
                                             * mapViewMap 里 slot5 的 MapView 视口是
                                             *   2560×1440
                                             * —— 读的是主屏 DisplayMetrics，不是我们的 VD。
                                             * 于是引擎按 2560×1440 的比例渲染地图，
                                             * 我们只抓左上 1920×532 那块，画面就成了
                                             * 「放大的道路局部」（踩了很久）。
                                             *
                                             * 正确做法：构造 MapViewPortParam 设成 1920×532，
                                             * 连 screenWidth/Height 一起设（两个字段是 long，
                                             * 必须用 setLong，用 set 会抛类型不匹配）。
                                             *
                                             * MapView 只能通过 MapServiceManager.mapViewMap
                                             * 拿到；HashMap 的 get/values 不能直接用 frida
                                             * wrapper 调（会报 argument types do not match），
                                             * 要反射 invok + Java.cast 成 MapView。
                                             */
                                            try {
                                                var CH = Java.use('java.util.concurrent.ConcurrentHashMap');
                                                var MSM2 = Java.use(
                                                    'ecarx.navi.engine.manager.map.MapServiceManager');
                                                var msmInst = MSM2.getInstance();
                                                var fmv = msmInst.getClass()
                                                    .getDeclaredField('mapViewMap');
                                                fmv.setAccessible(true);
                                                var mGet = CH.class.getMethod('get',
                                                    [Java.use('java.lang.Object').class]);
                                                var MapV = Java.use('com.autonavi.gbl.map.MapView');
                                                var mv5r = mGet.invoke(fmv.get(msmInst),
                                                    [Java.use('java.lang.Integer').valueOf(5)]);
                                                var mv5 = mv5r ? Java.cast(mv5r, MapV) : null;
                                                if (mv5) {
                                                    var PP = Java.use(
                                                        'com.autonavi.gbl.map.model.MapViewPortParam');
                                                    var pp = PP.$new();
                                                    var sfL = function (o, n, v) {
                                                        var fd = o.getClass()
                                                            .getDeclaredField(n);
                                                        fd.setAccessible(true);
                                                        fd.setLong(o, v);
                                                    };
                                                    sfL(pp, 'x', 0);
                                                    sfL(pp, 'y', 0);
                                                    sfL(pp, 'width', W);
                                                    sfL(pp, 'height', H);
                                                    sfL(pp, 'screenWidth', W);
                                                    sfL(pp, 'screenHeight', H);
                                                    var before = mv5.getMapviewPort();
                                                    mv5.setMapviewPort(pp);
                                                    var after = mv5.getMapviewPort();
                                                    log('viewport ' + before.width.value + 'x'
                                                        + before.height.value + ' → '
                                                        + after.width.value + 'x'
                                                        + after.height.value);
                                                } else {
                                                    log('取不到 slot5 的 MapView');
                                                }
                                            } catch (eVP) {
                                                log('viewport 修正失败: '
                                                    + String(eVP).substring(0, 80));
                                            }

                                            /*
                                             * ★ 把所有导航开关打开（2026-11-08）
                                             *
                                             * **必须用 .overload().call()**，不能 svc.initMapParam(mp)。
                                             * 直接调用会报 "svc.initMapParam is not a function"，
                                             * 因为 frida 对重载方法要求显式指定签名。
                                             *
                                             * *.value = true 那几行也必须走反射：这些字段
                                             * 是 private boolean / private float，frida 里
                                             * mp.isTmcVisible 是 undefined（不是 wrapper），
                                             * 直接赋值会抛 TypeError。
                                             */
                                            var MP = Java.use(
                                                'ecarx.navi.engine.bean.map.ExternalMapParam');
                                            var mp = MP.$new();
                                            var sfB = function (o, n, v) {
                                                try {
                                                    var fd = o.getClass().getDeclaredField(n);
                                                    fd.setAccessible(true);
                                                    fd.setBoolean(o, v);
                                                } catch (e) {}
                                            };
                                            sfB(mp, 'isTmcVisible', true);
                                            sfB(mp, 'isCrossVisible', true);
                                            sfB(mp, 'isDynamicLevel', true);
                                            sfB(mp, 'isLayerVisible', true);
                                            sfB(mp, 'isShowMutiRoute', true);
                                            try {
                                                var fdLv = mp.getClass()
                                                    .getDeclaredField('mapLevel');
                                                fdLv.setAccessible(true);
                                                fdLv.setFloat(mp, 17.0);
                                            } catch (e) {}
                                            try {
                                                var HS = Java.use(
                                                    'ecarx.navi.business.external.HUDService');
                                                HS.initMapParam.overload(
                                                    'ecarx.navi.engine.bean.map.ExternalMapParam'
                                                ).call(svc, mp);
                                                log('initMapParam OK（路况/路口/图层全开）');
                                            } catch (e2) {
                                                log('initMapParam 失败 '
                                                    + String(e2).substring(0, 70));
                                            }

                                            /*
                                             * ★ 3D 车模（验证成功，2026-11-08）
                                             *
                                             * 参考源码 hud_effects.js:91-116 applyCarStyle()。
                                             * 默认只有一个蓝色三角箭头，因为：
                                             *   · carMode 虽然是 2 (CAR_MODEL)
                                             *   · setSkeletonData 也成功
                                             *   · 但 updateCarStyle(2002) 是「普通模式」
                                             *     → 引擎走 2D 箭头路径
                                             *
                                             * 三个必须一起做的动作：
                                             *   ① setSkeletonData(assets, CarLogoEnum.CAR_MODEL)
                                             *      加载 car_skeleton_logo/2/carLogoLow.dat（971KB 的 3D 模型）
                                             *   ② updateCarStyle(2001)  ← 关键！2001 = 导航中 = 3D 渲染
                                             *   ③ setCarMode(2, true)    ← 第二参数 true 才真正启用 3D
                                             *
                                             * 实测按源码写 updateCarStyle(isGuidance()?2001:2002) 时
                                             * isGuidance()=false → 走 2002 → 一直是箭头。
                                             * 强制 2001 后就变成 3D 车模了。
                                             *
                                             * setCarScale 放大一点让车模更显眼（1.5 是视觉上舒服的值）。
                                             */
                                            try {
                                                var LC5 = Java.use(
                                                    'ecarx.navi.engine.manager.layer.LayerController');
                                                var ml5 = LC5.getInstance().getMapLayer(5);
                                                if (ml5) {
                                                    var CLE = Java.use(
                                                        'ecarx.navi.engine.bean.CarLogoEnum');
                                                    var CHA = Java.use(
                                                        'ecarx.navi.utils.utils.ContextHolder');
                                                    var amA = CHA.getContext().getAssets();

                                                    ml5.setSkeletonData(amA, CLE.CAR_MODEL.value);
                                                    ml5.updateCarStyle(2001);
                                                    var cmOk = ml5.setCarMode(2, true);
                                                    ml5.setCarScale(1.5);
                                                    ml5.setCarAnimationSwitch(true);
                                                    log('3D 车模：setCarMode(2,true)=' + cmOk
                                                        + '  carMode=' + ml5.getCarMode()
                                                        + '  ready=' + ml5.isCarReady());
                                                }
                                            } catch (eCar) {
                                                log('车模设置失败: '
                                                    + String(eCar).substring(0, 80));
                                            }

                                            /*
                                             * ★ 底图元素可见性：路名 / 路况深度 / 道路（2026-11-08）
                                             *
                                             * 参考源码 hud_effects.js:55-64 + 232-239：
                                             *   const States = Java.use(
                                             *       'com.autonavi.gbl.map.model.MapViewStateType');
                                             *   function idState(name){ return Number(States[name].value); }
                                             *   const set = (name,on) =>
                                             *       biz.setMapViewState(idState(name), on);
                                             *
                                             * 实测问题：仪表上只有红绿灯和路线，**没有路名、没有路况**。
                                             * 原因是我们只调了 setTmcVisible(5,true) —— 那只是 TMC 主开关，
                                             * 底图上「路名文字」和「路况路段的深度信息」各自有独立的
                                             * MAP_VIEWSTATE_* 开关，默认没开。
                                             *
                                             * 关键 ID（从源码 names 表抄下来的，都是 MapViewStateType 的静态字段）：
                                             *   MAP_VIEWSTATE_IS_LABLE_ROADNAME_ON    ← 路名
                                             *   MAP_VIEWSTATE_IS_TMC_DEPTH_INFO_ON    ← 路况深度（红黄绿路段）
                                             *   MAP_VIEWSTATE_IS_ROAD_ON              ← 道路本身
                                             *   MAP_VIEWSTATE_IS_ROAD_ARROW_ON        ← 道路箭头
                                             *
                                             * biz 从 MapView 拿：view.getOperatorBusiness()。
                                             */
                                            try {
                                                var CHB = Java.use('java.util.concurrent.ConcurrentHashMap');
                                                var MSMb = Java.use(
                                                    'ecarx.navi.engine.manager.map.MapServiceManager');
                                                var msmb = MSMb.getInstance();
                                                var fb = msmb.getClass().getDeclaredField('mapViewMap');
                                                fb.setAccessible(true);
                                                var mGetB = CHB.class.getMethod('get',
                                                    [Java.use('java.lang.Object').class]);
                                                var MapVB = Java.use('com.autonavi.gbl.map.MapView');
                                                var mv5bRaw = mGetB.invoke(fb.get(msmb),
                                                    [Java.use('java.lang.Integer').valueOf(5)]);
                                                var mv5b = mv5bRaw
                                                    ? Java.cast(mv5bRaw, MapVB) : null;

                                                if (mv5b) {
                                                    var biz = mv5b.getOperatorBusiness();
                                                    var States = Java.use(
                                                        'com.autonavi.gbl.map.model.MapViewStateType');

                                                    /*
                                                     * **变量名必须叫 stateIds，不能叫 want**
                                                     * （2026-11-08 修「轮询 1 次就放弃」）
                                                     *
                                                     * JS 的 var 是函数作用域，不是块作用域。
                                                     * 外层有个 `var want = 20`（轮询次数上限，
                                                     * 见轮询循环的 `if (tries < want)`）。
                                                     * 这里再声明一个 `var want = [...]` 会把那个
                                                     * 数字**覆盖成数组** —— 于是
                                                     *   tries < want  →  1 < 数组  →  false
                                                     * 轮询只跑一次就报「HudActivity 未就绪」，
                                                     * 表现就是地图投屏永远停在"正在启动"。
                                                     */
                                                    var stateIds = [
                                                        'MAP_VIEWSTATE_IS_LABLE_ROADNAME_ON',
                                                        'MAP_VIEWSTATE_IS_TMC_DEPTH_INFO_ON',
                                                        'MAP_VIEWSTATE_IS_ROAD_ON',
                                                        'MAP_VIEWSTATE_IS_ROAD_ARROW_ON'
                                                    ];
                                                    var okN = 0;
                                                    for (var wi = 0; wi < stateIds.length; wi++) {
                                                        var nm = stateIds[wi];
                                                        try {
                                                            var st = States[nm].value;
                                                            biz.setMapViewState(st, true);
                                                            okN++;
                                                        } catch (eSt) {
                                                            log('  ' + nm + ' 失败: '
                                                                + String(eSt).substring(0, 50));
                                                        }
                                                    }
                                                    log('底图开关已开 ' + okN + '/' + stateIds.length
                                                        + '（路名/路况深度/道路/道路箭头）');
                                                } else {
                                                    log('取不到 MapView(5) 做底图开关');
                                                }
                                            } catch (eBiz) {
                                                log('底图开关失败: '
                                                    + String(eBiz).substring(0, 80));
                                            }

                                            /*
                                             * ★ hook updateCarStyle 防止导航时车模被覆盖（2026-11-08）
                                             *
                                             * 现象：车停着时是 3D 车模，一开导航就变回箭头。
                                             *
                                             * 原因：导航状态变化时，引擎/业务层会自己调
                                             * MapLayer.updateCarStyle(...)，把之前设的 2001 覆盖成
                                             * 2002（普通模式）→ 引擎退回 2D 箭头渲染。
                                             *
                                             * 源码用的是同一套思路（hud_effects.js:82-84 hook
                                             * HUDControl.updateMapTheme 保护主题不被 OEM 改）：
                                             * 把方法 hook 住，链到原实现之前先把参数改回我们要的值。
                                             *
                                             * 这里 hook MapLayer.updateCarStyle(int)：
                                             * 无论谁传什么，一律改成 2001（导航态 = 3D 车模）。
                                             * 注意要 return 原方法的返回值，否则调用方拿到 undefined 会出错。
                                             */
                                            try {
                                                var MLH = Java.use(
                                                    'ecarx.navi.engine.manager.layer.MapLayer');
                                                var ucs = MLH.updateCarStyle.overload('int');
                                                /*
                                                 * **必须用 ucs.call(this, 2001) 而不是
                                                 * this.updateCarStyle(2001)** —— 后者会再次
                                                 * 进入这个 implementation，无限递归直到栈溢出
                                                 * （一开导航就崩）。
                                                 *
                                                 * ucs.call 直接走原始实现，绕过 hook。
                                                 */
                                                ucs.implementation = function (style) {
                                                    return ucs.call(this, 2001);
                                                };
                                                log('updateCarStyle 已 hook（强制 2001，防导航时车模退化）');
                                            } catch (eHk) {
                                                log('hook updateCarStyle 失败: '
                                                    + String(eHk).substring(0, 70));
                                            }

                                            /*
                                             * ★ 视角调整：让车模居中偏上、后方留空（2026-11-08）
                                             *
                                             * 需求：车模 + 蓝标完整露出来，车后面（画面下方）留一点空，
                                             * 视觉上像是"车在画面里、视线往前看"。
                                             *
                                             * 现在的问题：车标被投影到画面正中（y ≈ H/2），
                                             * 上方的道路被压得很窄，下方也没有余量 —— 车模容易被裁。
                                             *
                                             * 原理：车标位置 = Camera center 在屏幕上的投影点。
                                             * 改它有两个手段：
                                             *   ① setPitchAngle —— 俯仰角。Pitch 越大视野越前倾，
                                             *      车模在画面里越靠上（3D 导航的标准做法，通常 30-50°）
                                             *   ② setMapCenterOffset / setViewPortOffset —— 直接给屏幕
                                             *      投影加纵向偏移（有的引擎版本叫别的名）
                                             *
                                             * 参考源码 hud_effects.js:251：
                                             *   view.getOperatorPosture().setPitchAngle(pitch)
                                             * 源码没做偏移，靠 pitch 就够了。
                                             *
                                             * 参数取值：
                                             *   PITCH_ANGLE = 45°  —— 标准 3D 导航视角
                                             *   CAR_Y_RATIO = 0.38 —— 车模目标纵向位置（0=顶, 1=底）
                                             *                         0.38 让车模略偏上、下方留 62% 的路况
                                             *
                                             * 实现：
                                             *   · 先设 pitch
                                             *   · 再算需要补的纵向偏移，用 posture 的 setter 补上
                                             *     （拿不到就只靠 pitch，也够用）
                                             */
                                            try {
                                                /* 复用上面底图开关那段取到的 mv5b，
                                                 * 不再重复反射 ConcurrentHashMap.get */
                                                if (mv5b) {
                                                    var posture = mv5b.getOperatorPosture();

                                                    /* ① 俯仰角：45° 标准 3D 导航视角 */
                                                    var PITCH = 45.0;
                                                    try {
                                                        posture.setPitchAngle(PITCH);
                                                        log('pitch = ' + posture.getPitchAngle());
                                                    } catch (eP) {
                                                        log('setPitchAngle 失败: '
                                                            + String(eP).substring(0, 50));
                                                    }

                                                    /*
                                                     * ② 纵向偏移：把车模推到画面 38% 高度处
                                                     *
                                                     * posture 可能暴露 setViewPortY / setMapCenterY
                                                     * 之类的接口（不同引擎版本名字不一样），
                                                     * 逐个试，谁成功用谁。都不成功也不要紧 ——
                                                     * pitch=45 本身就能把车模推到接近目标位置。
                                                     */
                                                    var CAR_Y_RATIO = 0.38;
                                                    var targetY = Math.round(H * CAR_Y_RATIO);
                                                    var centerY = Math.round(H / 2);
                                                    var dy = targetY - centerY;   /* 负值 = 上移 */
                                                    var done = false;
                                                    var cands = [
                                                        'setViewPortOffset', 'setViewportOffset',
                                                        'setMapCenterOffset', 'setCenterOffset'
                                                    ];
                                                    for (var ci = 0; ci < cands.length && !done; ci++) {
                                                        try {
                                                            var mn = cands[ci];
                                                            posture[mn].overload('int', 'int')
                                                                  .call(posture, 0, dy);
                                                            log('  ' + mn + '(0,' + dy + ') OK');
                                                            done = true;
                                                        } catch (e1) {
                                                            try {
                                                                posture[cands[ci]]
                                                                    .overload('float', 'float')
                                                                    .call(posture, 0.0, dy * 1.0);
                                                                log('  ' + cands[ci] + '(0,' + dy + ') OK(float)');
                                                                done = true;
                                                            } catch (e2) {}
                                                        }
                                                    }
                                                    if (!done) {
                                                        log('  无纵向偏移接口，仅靠 pitch=45°（视觉上已接近目标）');
                                                    }

                                                    /* ③ 缩放：17 是导航标准级别，保持不动 */
                                                    try {
                                                        log('  zoom = ' + posture.getZoomLevel());
                                                    } catch (eZ) {}
                                                } else {
                                                    log('取不到 MapView(5) 做视角调整');
                                                }
                                            } catch (eView) {
                                                log('视角调整失败: '
                                                    + String(eView).substring(0, 80));
                                            }

                                            startPc();
                                            return;
                                        }
                                    }
                                } catch (e) {
                                    if (tries <= 3) log('轮询 ' + tries + ' :: ' + String(e).substring(0, 70));
                                }
                                if (tries < want) {
                                    CTX.hh.postDelayed(pollInst, 1500);
                                } else {
                                    log('HudActivity 未就绪（轮询 ' + tries + ' 次后放弃）');
                                }
                            }
                        }
                    });
                    var pollInst = Poll.$new();
                    CTX.hh.postDelayed(pollInst, 1500);
                    log('等待 HudActivity 初始化…');
                } catch (e) {
                    log('主流程异常: ' + String(e).substring(0, 130));
                }
            }
        }
    });

    /* ── PixelCopy 链（等 HudActivity 就绪后再启动） ── */

    CTX.hh.post(Main.$new());
});
