package tools.jackson.dataformat.javaprop.io;

import tools.jackson.core.TokenStreamContext;

public class JPropWriteContext
    extends TokenStreamContext
{
    /**
     * Parent context for this context; null for root context.
     */
    protected final JPropWriteContext _parent;

    /*
    /**********************************************************************
    /* Simple instance reuse slots; speed up things
    /* a bit (10-15%) for docs with lots of small arrays/objects
    /**********************************************************************
     */

    protected JPropWriteContext _child = null;
    
    /*
    /**********************************************************************
    /* Location/state information (minus source reference)
    /**********************************************************************
     */
    
    /**
     * Value that is being serialized and caused this context to be created;
     * typically a POJO or container type.
     */
    protected Object _currentValue;

    /**
     * Marker used to indicate that we just received a name, and
     * now expect a value
     */
    protected boolean _gotName;
    
    /**
     * Name of the field of which value is to be parsed; only
     * used for OBJECT contexts
     */
    protected String _currentName;
    
    protected int _basePathLength;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */
    
    protected JPropWriteContext(int type, JPropWriteContext parent,
            Object currValue, int basePathLength)
    {
        super();
        _type = type;
        _parent = parent;
        _nestingDepth = parent == null ? 0 : parent._nestingDepth + 1;
        _basePathLength = basePathLength;
        _index = -1;
        _currentValue = currValue;
    }

    private void reset(int type, Object currValue, int basePathLength) {
        _type = type;
        _basePathLength = basePathLength;
        _currentValue = null;
        _index = -1;
        _currentValue = currValue;
    }
    
    // // // Factory methods

    public static JPropWriteContext createRootContext() {
        return new JPropWriteContext(TYPE_ROOT, null, null, 0);
    }

    public static JPropWriteContext createRootContext(int basePathLength) {
        return new JPropWriteContext(TYPE_ROOT, null, null, basePathLength);
    }
    
    public JPropWriteContext createChildArrayContext(Object currValue, int basePathLength) {
        JPropWriteContext ctxt = _child;
        if (ctxt == null) {
            _child = ctxt = new JPropWriteContext(TYPE_ARRAY, this, currValue, basePathLength);
            return ctxt;
        }
        ctxt.reset(TYPE_ARRAY, currValue, basePathLength);
        return ctxt;
    }

    public JPropWriteContext createChildObjectContext(Object currValue, int basePathLength) {
        JPropWriteContext ctxt = _child;
        if (ctxt == null) {
            _child = ctxt = new JPropWriteContext(TYPE_OBJECT, this, currValue, basePathLength);
            return ctxt;
        }
        ctxt.reset(TYPE_OBJECT, currValue, basePathLength);
        return ctxt;
    }

    /*
    /**********************************************************************
    /* State changes
    /**********************************************************************
     */

    public boolean writeName(String name)
    {
        if (_gotName) {
            return false;
        }
        _gotName = true;
        _currentName = name;
        return true;
    }

    public boolean writeValue() {
        // Most likely, object:
        if (_type == TYPE_OBJECT) {
            if (!_gotName) {
                return false;
            }
            _gotName = false;
        }
        // Array fine, and must allow root context for Object values too so...
        ++_index;
        return true;
    }

    public void truncatePath(StringBuilder sb) {
        int len = sb.length();
        if (len != _basePathLength) {
            if (len < _basePathLength) { // sanity check
                throw new IllegalStateException(String.format
                        ("Internal error: base path length %d, buffered %d, trying to truncate",
                                _basePathLength, len));
            }
            sb.setLength(_basePathLength);
        }
    }

    /*
    /**********************************************************************
    /* Simple accessors, mutators
    /**********************************************************************
     */
    
    @Override
    public final JPropWriteContext getParent() { return _parent; }
    
    @Override
    public String currentName() {
        return _currentName;
    }

    @Override
    public Object currentValue() {
        return _currentValue;
    }

    @Override
    public void assignCurrentValue(Object v) {
        _currentValue = v;
    }

    public StringBuilder appendDesc(StringBuilder sb) {
        if (_parent != null) {
            sb = _parent.appendDesc(sb);
            sb.append('/');
        }
        switch (_type) {
        case TYPE_OBJECT:
            if (_currentName != null) {
                sb.append(_currentName);
            }
            break;
        case TYPE_ARRAY:
            sb.append(getCurrentIndex());
            break;
        case TYPE_ROOT:
        }
        return sb;
    }
    
    // // // Overridden standard methods
    
    /**
     * Overridden to provide developer JsonPointer representation
     * of the context.
     */
    @Override
    public final String toString() {
        return appendDesc(new StringBuilder(64)).toString();
    }
}
