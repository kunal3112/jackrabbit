/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.value;

import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.core.data.DataIdentifier;
import org.apache.jackrabbit.core.data.DataStore;
import org.apache.jackrabbit.core.fs.FileSystemResource;
import org.apache.jackrabbit.spi.commons.conversion.MalformedPathException;
import org.apache.jackrabbit.spi.commons.conversion.NameException;
import org.apache.jackrabbit.spi.commons.conversion.NamePathResolver;
import org.apache.jackrabbit.spi.Path;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.QValue;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.jackrabbit.uuid.UUID;
import org.apache.jackrabbit.value.StringValue;
import org.apache.jackrabbit.value.URIValue;
import org.apache.jackrabbit.spi.commons.name.PathFactoryImpl;
import org.apache.jackrabbit.spi.commons.name.NameFactoryImpl;
import org.apache.jackrabbit.spi.commons.value.ValueFactoryQImpl;
import org.apache.jackrabbit.spi.commons.value.AbstractQValue;
import org.apache.jackrabbit.spi.commons.value.QValueValue;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.net.URI;
import java.net.URISyntaxException;
import java.math.BigDecimal;

/**
 * <code>InternalValue</code> represents the internal format of a property value.
 * <p/>
 * The following table specifies the internal format for every property type:
 * <pre>
 * <table>
 * <tr><b>PropertyType</b><td></td><td><b>Internal Format</b></td></tr>
 * <tr>STRING<td></td><td>String</td></tr>
 * <tr>LONG<td></td><td>Long</td></tr>
 * <tr>DOUBLE<td></td><td>Double</td></tr>
 * <tr>DATE<td></td><td>Calendar</td></tr>
 * <tr>BOOLEAN<td></td><td>Boolean</td></tr>
 * <tr>NAME<td></td><td>Name</td></tr>
 * <tr>PATH<td></td><td>Path</td></tr>
 * <tr>URI<td></td><td>URI</td></tr>
 * <tr>DECIMAL<td></td><td>BigDecimal</td></tr>
 * <tr>BINARY<td></td><td>BLOBFileValue</td></tr>
 * <tr>REFERENCE<td></td><td>UUID</td></tr>
 * </table>
 * </pre>
 */
public class InternalValue extends AbstractQValue {

    public static final InternalValue[] EMPTY_ARRAY = new InternalValue[0];

    private static final InternalValue BOOLEAN_TRUE = new InternalValue(true);

    private static final InternalValue BOOLEAN_FALSE = new InternalValue(false);

    /**
     * If set to 'true', the data store is used when configured in repository.xml
     */
    public static final boolean USE_DATA_STORE =
        Boolean.valueOf(System.getProperty("org.jackrabbit.useDataStore", "true")).booleanValue();

    /**
     * Temporary binary values smaller or equal this size are kept in memory
     */
    private static final int MIN_BLOB_FILE_SIZE = 1024;

    //------------------------------------------------------< factory methods >
    /**
     * Create a new internal value from the given JCR value.
     * Large binary values are stored in a temporary file.
     *
     * @param value the JCR value
     * @param resolver
     * @return the created internal value
     */
    public static InternalValue create(Value value, NamePathResolver resolver)
            throws ValueFormatException, RepositoryException {
        return create(value, resolver, null);
    }

    /**
     * Create a new internal value from the given JCR value.
     * If the data store is enabled, large binary values are stored in the data store.
     *
     * @param value the JCR value
     * @param resolver
     * @param store the data store
     * @return the created internal value
     */
    public static InternalValue create(Value value, NamePathResolver resolver, DataStore store)
            throws ValueFormatException, RepositoryException {
        switch (value.getType()) {
            case PropertyType.BINARY:
                InternalValue result;
                if (USE_DATA_STORE) {
                    BLOBFileValue blob = null;
                    if (value instanceof BinaryValueImpl) {
                        BinaryValueImpl bin = (BinaryValueImpl) value;
                        DataIdentifier identifier = bin.getDataIdentifier();
                        if (identifier != null) {
                            // access the record to ensure it is not garbage collected
                            if (store.getRecordIfStored(identifier) != null) {
                                // it exists - so we don't need to stream it again
                                // but we need to create a new object because the original
                                // one might be in a different data store (repository)
                                blob = BLOBInDataStore.getInstance(store, identifier);
                            }
                        }
                    }
                    if (blob == null) {
                        blob = getBLOBFileValue(store, value.getStream(), true);
                    }
                    result = new InternalValue(blob);
                } else if (value instanceof BLOBFileValue) {
                    result = new InternalValue((BLOBFileValue) value);
                } else {
                    InputStream stream = value.getStream();
                    try {
                        result = createTemporary(stream);
                    } finally {
                        IOUtils.closeQuietly(stream);
                    }
                }
                return result;
            case PropertyType.BOOLEAN:
                return create(value.getBoolean());
            case PropertyType.DATE:
                return create(value.getDate());
            case PropertyType.DOUBLE:
                return create(value.getDouble());
            case PropertyType.DECIMAL:
                return create(value.getDecimal());
            case PropertyType.LONG:
                return create(value.getLong());
            case PropertyType.REFERENCE:
                return create(new UUID(value.getString()));
            case PropertyType.WEAKREFERENCE:
                return create(new UUID(value.getString()), true);
            case PropertyType.URI:
                try {
                    return create(new URI(value.getString()));
                } catch (URISyntaxException e) {
                    throw new ValueFormatException(e.getMessage());
                }
            case PropertyType.NAME:
                try {
                    if (value instanceof QValueValue) {
                        QValue qv = ((QValueValue) value).getQValue();
                        if (qv instanceof InternalValue) {
                            return (InternalValue) qv;
                        } else {
                            return create(qv.getName());
                        }
                    } else {
                        return create(resolver.getQName(value.getString()));
                    }
                } catch (NameException e) {
                    throw new ValueFormatException(e.getMessage());
                }
            case PropertyType.PATH:
                try {
                    if (value instanceof QValueValue) {
                        QValue qv = ((QValueValue) value).getQValue();
                        if (qv instanceof InternalValue) {
                            return (InternalValue) qv;
                        } else {
                            return create(qv.getPath());
                        }
                    } else {
                        return create(resolver.getQPath(value.getString(), false));
                    }
                } catch (MalformedPathException mpe) {
                    throw new ValueFormatException(mpe.getMessage());
                }
            case PropertyType.STRING:
                return create(value.getString());
            default:
                throw new IllegalArgumentException("illegal value");
        }
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(String value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(long value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(double value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(Calendar value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(BigDecimal value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(URI value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(boolean value) {
        return value ? BOOLEAN_TRUE : BOOLEAN_FALSE;
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(byte[] value) {
        if (USE_DATA_STORE) {
            return new InternalValue(BLOBInMemory.getInstance(value));
        }
        return new InternalValue(new BLOBValue(value));
    }

    /**
     * Create an internal value that is backed by a temporary file.
     *
     * @param value the stream
     * @return the internal value
     */
    public static InternalValue createTemporary(InputStream value) throws RepositoryException {
        if (USE_DATA_STORE) {
            return new InternalValue(getBLOBFileValue(null, value, true));
        }
        try {
            return new InternalValue(new BLOBValue(value, true));
        } catch (IOException e) {
            throw new RepositoryException("Error creating temporary file", e);
        }
    }

    /**
     * Create an internal value that is backed by a temporary file
     * (if data store usage is disabled or the store is null)
     * or in the data store if it is not null and enabled.
     *
     * @param value the stream
     * @param store the data store or null to use a temporary file
     * @return the internal value
     */
    public static InternalValue createTemporary(InputStream value, DataStore store) throws RepositoryException {
        if (USE_DATA_STORE) {
            return new InternalValue(getBLOBFileValue(store, value, true));
        }
        try {
            return new InternalValue(new BLOBValue(value, true));
        } catch (IOException e) {
            throw new RepositoryException("Error creating temporary file", e);
        }
    }

    /**
     * @param value
     * @return
     * @throws IOException
     */
    public static InternalValue create(InputStream value) throws RepositoryException {
        if (USE_DATA_STORE) {
            return new InternalValue(getBLOBFileValue(null, value, false));
        }
        try {
            return new InternalValue(new BLOBValue(value, false));
        } catch (IOException e) {
            throw new RepositoryException("Error creating file", e);
        }
    }

    /**
     * @param value
     * @return
     * @throws IOException
     */
    public static InternalValue create(FileSystemResource value) throws IOException {
        if (USE_DATA_STORE) {
            return new InternalValue(BLOBInResource.getInstance(value));
        }
        return new InternalValue(new BLOBValue(value));
    }

    /**
     * @param value
     * @return
     * @throws IOException
     */
    public static InternalValue create(File value) throws IOException {
        assert !USE_DATA_STORE;
        return new InternalValue(new BLOBValue(value));
    }

    /**
     * Create a binary object with the given identifier.
     *
     * @param store the data store
     * @param id the identifier
     * @return the value
     */
    public static InternalValue create(DataStore store, String id) {
        assert USE_DATA_STORE && store != null;
        return new InternalValue(getBLOBFileValue(store, id));
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(Name value) {
        return new InternalValue(value);
    }

    /**
     * @param values
     * @return the created value
     */
    public static InternalValue[] create(Name[] values) {
        InternalValue[] ret = new InternalValue[values.length];
        for (int i = 0; i < values.length; i++) {
            ret[i] = new InternalValue(values[i]);
        }
        return ret;
    }

    /**
     * @param values
     * @return the created value
     */
    public static InternalValue[] create(String[] values) {
        InternalValue[] ret = new InternalValue[values.length];
        for (int i = 0; i < values.length; i++) {
            ret[i] = new InternalValue(values[i]);
        }
        return ret;
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(Path value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(UUID value) {
        return create(value, false);
    }

    /**
     * @param value
     * @param weak
     * @return the created value
     */
    public static InternalValue create(UUID value, boolean weak) {
        return new InternalValue(value, weak);
    }

    //----------------------------------------------------< conversions, etc. >
    /**
     * @param resolver
     * @return
     * @throws RepositoryException
     * @deprecated
     */
    public Value toJCRValue(NamePathResolver resolver)
            throws RepositoryException {
        ValueFactory vf;
        if (resolver instanceof Session) {
            vf = ((Session) resolver).getValueFactory();
        } else {
            vf = new ValueFactoryImpl(resolver);
        }

        if (vf instanceof ValueFactoryQImpl) {
            return ((ValueFactoryQImpl) vf).createValue(this);
        } else {
            switch (type) {
                case PropertyType.BINARY:
                    return vf.createValue(((BLOBFileValue) val).getStream());
                case PropertyType.BOOLEAN:
                    return vf.createValue(((Boolean) val).booleanValue());
                case PropertyType.DATE:
                    return vf.createValue((Calendar) val);
                case PropertyType.DOUBLE:
                    return vf.createValue(((Double) val).doubleValue());
                case PropertyType.LONG:
                    return vf.createValue(((Long) val).longValue());
                case PropertyType.DECIMAL:
                    return vf.createValue((BigDecimal) val);
                case PropertyType.REFERENCE:
                    return vf.createValue(val.toString(), PropertyType.REFERENCE);
                case PropertyType.WEAKREFERENCE:
                    return vf.createValue(val.toString(), PropertyType.WEAKREFERENCE);
                case PropertyType.URI:
                    return new URIValue((URI) val);
                case PropertyType.PATH:
                    return vf.createValue(resolver.getJCRPath((Path) val), PropertyType.PATH);
                case PropertyType.NAME:
                    return vf.createValue(resolver.getJCRName((Name) val), PropertyType.NAME);
                case PropertyType.STRING:
                    return new StringValue((String) val);
                default:
                    throw new RepositoryException("illegal internal value type");
            }
        }
    }

    /**
     * @deprecated
     * @return the internal object
     */
    public Object internalValue() {
        return val;
    }

    public BLOBFileValue getBLOBFileValue() {
        assert val != null && type == PropertyType.BINARY;
        return (BLOBFileValue) val;
    }

    public UUID getUUID() {
        assert val != null && (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE);
        return (UUID) val;
    }

    public Name getQName() {
        assert val != null && type == PropertyType.NAME;
        return (Name) val;
    }

    public Calendar getDate() {
        assert val != null && type == PropertyType.DATE;
        return (Calendar) val;
    }

    /**
     * Create a copy of this object. Immutable values will return itself,
     * while mutable values will return a copy.
     *
     * @return itself or a copy
     * @throws RepositoryException
     */
    public InternalValue createCopy() throws RepositoryException {
        if (type != PropertyType.BINARY) {
            // for all types except BINARY it's safe to return 'this' because the
            // wrapped value is immutable (and therefore this instance as well)
            return this;
        }
        BLOBFileValue v = (BLOBFileValue) val;
        if (USE_DATA_STORE) {
            if (v.isImmutable()) {
                return this;
            }
        }
        // return a copy since the wrapped BLOBFileValue instance is mutable
        InputStream stream = v.getStream();
        try {
            return createTemporary(stream);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Parses the given string as an <code>InternalValue</code> of the
     * specified type. The string must be in the format returned by the
     * <code>InternalValue.toString()</code> method.
     *
     * @param s a <code>String</code> containing the <code>InternalValue</code>
     *          representation to be parsed.
     * @param type
     * @return the <code>InternalValue</code> represented by the arguments
     * @throws IllegalArgumentException if the specified string can not be parsed
     *                                  as an <code>InternalValue</code> of the
     *                                  specified type.
     * @see #toString()
     */
    public static InternalValue valueOf(String s, int type) {
        switch (type) {
            case PropertyType.BOOLEAN:
                return create(Boolean.valueOf(s).booleanValue());
            case PropertyType.DATE:
                return create(ISO8601.parse(s));
            case PropertyType.DOUBLE:
                return create(Double.parseDouble(s));
            case PropertyType.LONG:
                return create(Long.parseLong(s));
            case PropertyType.DECIMAL:
                return create(new BigDecimal(s));
            case PropertyType.REFERENCE:
                return create(new UUID(s));
            case PropertyType.WEAKREFERENCE:
                return create(new UUID(s), true);
            case PropertyType.PATH:
                return create(PathFactoryImpl.getInstance().create(s));
            case PropertyType.NAME:
                return create(NameFactoryImpl.getInstance().create(s));
            case PropertyType.URI:
                return create(URI.create(s));
            case PropertyType.STRING:
                return create(s);

            case PropertyType.BINARY:
                throw new IllegalArgumentException(
                        "this method does not support the type PropertyType.BINARY");
            default:
                throw new IllegalArgumentException("illegal type: " + type);
        }
    }

    //-------------------------------------------< java.lang.Object overrides >
    /**
     * Returns the string representation of this internal value.
     *
     * @return string representation of this internal value
     */
    public String toString() {
        if (type == PropertyType.DATE) {
            return ISO8601.format((Calendar) val);
        } else {
            return val.toString();
        }
    }


    //-------------------------------------------------------< implementation >
    private InternalValue(String value) {
        super(value, PropertyType.STRING);
    }

    private InternalValue(Name value) {
        super(value);
    }

    private InternalValue(long value) {
        super(Long.valueOf(value));
    }

    private InternalValue(double value) {
        super(Double.valueOf(value));
    }

    private InternalValue(Calendar value) {
        super(value, PropertyType.DATE);
    }

    private InternalValue(boolean value) {
        super(Boolean.valueOf(value));
    }

    private InternalValue(URI value) {
        super(value, PropertyType.URI);
    }

    private InternalValue(BigDecimal value) {
        super(value, PropertyType.DECIMAL);
    }

    private InternalValue(BLOBFileValue value) {
        super(value, PropertyType.BINARY);
    }

    private InternalValue(Path value) {
        super(value);
    }

    private InternalValue(UUID value) {
        super(value, PropertyType.REFERENCE);
    }

    private InternalValue(UUID value, boolean weak) {
        super(value, weak ? PropertyType.WEAKREFERENCE : PropertyType.REFERENCE);
    }

    /**
     * Create a BLOB value from in input stream. Small objects will create an in-memory object,
     * while large objects are stored in the data store or in a temp file (if the store parameter is not set).
     *
     * @param store the data store (optional)
     * @param in the input stream
     * @param temporary if the file should be deleted when discard is called (ignored if a data store is used)
     * @return the value
     */
    private static BLOBFileValue getBLOBFileValue(DataStore store, InputStream in, boolean temporary) throws RepositoryException {
        int maxMemorySize;
        if (store != null) {
            maxMemorySize = store.getMinRecordLength() - 1;
        } else {
            maxMemorySize = MIN_BLOB_FILE_SIZE;
        }
        maxMemorySize = Math.max(0, maxMemorySize);
        byte[] buffer = new byte[maxMemorySize];
        int pos = 0, len = maxMemorySize;
        try {
            while (pos < maxMemorySize) {
                int l = in.read(buffer, pos, len);
                if (l < 0) {
                    break;
                }
                pos += l;
                len -= l;
            }
        } catch (IOException e) {
            throw new RepositoryException("Could not read from stream", e);
        }
        if (pos < maxMemorySize) {
            // shrink the buffer
            byte[] data = new byte[pos];
            System.arraycopy(buffer, 0, data, 0, pos);
            return BLOBInMemory.getInstance(data);
        } else {
            // a few bytes are already read, need to re-build the input stream
            in = new SequenceInputStream(new ByteArrayInputStream(buffer, 0, pos), in);
            if (store != null) {
                return BLOBInDataStore.getInstance(store, in);
            } else {
                return BLOBInTempFile.getInstance(in, temporary);
            }
        }
    }

    private static BLOBFileValue getBLOBFileValue(DataStore store, String id) {
        if (BLOBInMemory.isInstance(id)) {
            return BLOBInMemory.getInstance(id);
        } else if (BLOBInDataStore.isInstance(id)) {
            return BLOBInDataStore.getInstance(store, id);
        } else {
            throw new IllegalArgumentException("illegal binary id: " + id);
        }
    }

    /**
     * Store a value in the data store. This will store temporary files or in-memory objects
     * in the data store.
     *
     * @param dataStore the data store
     * @throws RepositoryException
     */
    public void store(DataStore dataStore) throws RepositoryException, IOException {
        assert USE_DATA_STORE;
        assert dataStore != null;
        assert type == PropertyType.BINARY;
        BLOBFileValue v = (BLOBFileValue) val;
        if (v instanceof BLOBInDataStore) {
            // already in the data store, OK
            return;
        } else if (v instanceof BLOBInMemory) {
            if (v.getLength() < dataStore.getMinRecordLength()) {
                // in memory and does not make sense to store, OK
                return;
            }
        }
        // store the temp file to the data store, or (theoretically) load it in memory
        val = getBLOBFileValue(dataStore, v.getStream(), false);
    }

    //-------------------------------------------------------------< QValue >---
    /**
     * @see org.apache.jackrabbit.spi.QValue#getLength()
     */
    public long getLength() throws RepositoryException {
        if (PropertyType.BINARY == type) {
            return ((BLOBFileValue) val).getLength();
        } else {
            return super.getLength();
        }
    }

    /**
     * @see org.apache.jackrabbit.spi.QValue#getString()
     */
    public String getString() throws RepositoryException {
        if (type == PropertyType.BINARY) {
            return ((BLOBFileValue) val).getString();
        } else if (type == PropertyType.DATE) {
            return ISO8601.format(((Calendar) val));
        } else {
            return toString();
        }
    }

    /**
     * @see org.apache.jackrabbit.spi.QValue#getStream()
     */
    public InputStream getStream() throws RepositoryException {
        if (type == PropertyType.BINARY) {
            return ((BLOBFileValue) val).getStream();
        } else {
            try {
                // convert via string
                return new ByteArrayInputStream(getString().getBytes(InternalValueFactory.DEFAULT_ENCODING));
            } catch (UnsupportedEncodingException e) {
                throw new RepositoryException(InternalValueFactory.DEFAULT_ENCODING + " is not supported encoding on this platform", e);
            }
        }
    }
}
