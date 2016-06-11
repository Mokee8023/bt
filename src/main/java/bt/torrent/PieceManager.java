package bt.torrent;

import bt.BtException;
import bt.data.DataStatus;
import bt.data.IChunkDescriptor;
import bt.net.PeerConnection;
import bt.protocol.InvalidMessageException;
import bt.protocol.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class PieceManager implements IPieceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PieceManager.class);

    private List<IChunkDescriptor> chunks;
    private int completePieces;

    /**
     * Indicates if there is at least one verified chunk in the local torrent files.
     */
    private volatile boolean haveAnyData;
    private byte[] bitfield;

    private PieceSelectorHelper pieceSelectorHelper;
    private Assignments assignments;

    public PieceManager(PieceSelector selector, List<IChunkDescriptor> chunks) {

        this.chunks = chunks;

        bitfield = createBitfield(chunks);
        for (byte b : bitfield) {
            if (b != 0) {
                haveAnyData = true;
                break;
            }
        }

        pieceSelectorHelper = new PieceSelectorHelper(new PieceStats(chunks.size()), selector,
                pieceIndex -> !checkPieceCompleted(pieceIndex), bitfield.length);

        assignments = new Assignments();
    }

    /**
     * Creates a standard bittorrent bitfield, where n-th bit
     * (couting from high position to low) indicates the availability of n-th piece.
     */
    private byte[] createBitfield(List<IChunkDescriptor> chunks) {

        int chunkCount = chunks.size();
        byte[] bitfield = new byte[(int) Math.ceil(chunkCount / 8d)];
        int bitfieldIndex = 0;
        while (chunkCount > 0) {
            int b = 0, offset = bitfieldIndex * 8;
            int k = chunkCount < 8? chunkCount : 8;
            for (int i = 0; i < k; i++) {
                IChunkDescriptor chunk = chunks.get(offset + i);
                if (chunk.getStatus() == DataStatus.VERIFIED) {
                    b += 0b1 << (7 - i);
                    completePieces++;
                }
            }
            bitfield[bitfieldIndex] = (byte) b;
            bitfieldIndex++;
            chunkCount -= 8;
        }
        return bitfield;
    }

    @Override
    public boolean haveAnyData() {
        return haveAnyData;
    }

    @Override
    public byte[] getBitfield() {
        return Arrays.copyOf(bitfield, bitfield.length);
    }

    // TODO: Check if peer has everything (i.e. is a seeder) and store him separately
    // this should help to improve performance of the next piece selection algorithm
    @Override
    public void peerHasBitfield(PeerConnection peer, byte[] peerBitfield) {

        if (peerBitfield.length == bitfield.length) {
            byte[] bs = Arrays.copyOf(peerBitfield, peerBitfield.length);
            pieceSelectorHelper.addPeerBitfield(peer, bs);
        } else {
            throw new BtException("bitfield has wrong size: " + peerBitfield.length);
        }
    }

    @Override
    public void peerHasPiece(PeerConnection peer, Integer pieceIndex) {
        validatePieceIndex(pieceIndex);
        pieceSelectorHelper.addPeerPiece(peer, pieceIndex);
    }

    @Override
    public boolean checkPieceCompleted(Integer pieceIndex) {
        try {
            if (getBit(bitfield, pieceIndex) == 1) {
                return true;
            }

            IChunkDescriptor chunk = chunks.get(pieceIndex);
            if (chunk.verify()) {
                setBit(bitfield, pieceIndex);
                haveAnyData = true;
                completePieces++;
                assignments.removeAssignee(pieceIndex);
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to verify chunk {piece index: " + pieceIndex + "}", e);
        }
        return false;
    }

    @Override
    public boolean mightSelectPieceForPeer(PeerConnection peer) {
        Optional<Integer> piece = pieceSelectorHelper.selectPieceForPeer(peer);
        return piece.isPresent() && !assignments.getAssignee(piece.get()).isPresent();
    }

    @Override
    public Optional<Integer> selectPieceForPeer(PeerConnection peer) {
        Optional<Integer> assignedPiece = assignments.getAssignedPiece(peer);
        return assignedPiece.isPresent()? assignedPiece : selectAndAssignPiece(peer);
    }

    private Optional<Integer> selectAndAssignPiece(PeerConnection peer) {
        Optional<Integer> piece = pieceSelectorHelper.selectPieceForPeer(peer);
        if (piece.isPresent()) {
            assignments.assignPiece(peer, piece.get());
        }
        return piece;
    }

    @Override
    public List<Request> buildRequestsForPiece(Integer pieceIndex) {

        validatePieceIndex(pieceIndex);

        List<Request> requests = new ArrayList<>();

        IChunkDescriptor chunk = chunks.get(pieceIndex);
        byte[] bitfield = chunk.getBitfield();
        long blockSize = chunk.getBlockSize(),
             chunkSize = chunk.getSize();

        for (int i = 0; i < bitfield.length; i++) {
            if (bitfield[i] == 0) {
                int offset = (int) (i * blockSize);
                int length = (int) Math.min(blockSize, chunkSize - offset);
                try {
                    requests.add(new Request(pieceIndex, offset, length));
                } catch (InvalidMessageException e) {
                    // shouldn't happen
                    throw new BtException("Unexpected error", e);
                }
            }
        }
        return requests;
    }

    @Override
    public int piecesLeft() {

        int left = chunks.size() - completePieces;
        if (left < 0) {
            // some algorithm malfunction
            throw new BtException("Unexpected number of pieces left: " + left);
        }
        return left;
    }

    private void validatePieceIndex(Integer pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= chunks.size()) {
            throw new BtException("Illegal piece index: " + pieceIndex);
        }
    }

    /**
     * Sets n-th bit in the bitfield
     * (which is considered a continuous bit array of bits, indexed starting with 0 from left to right)
     */
    private static void setBit(byte[] bitfield, int bitAbsIndex) {

        int byteIndex = (int) (bitAbsIndex / 8d);
        if (byteIndex >= bitfield.length) {
            throw new BtException("bit index is too large: " + bitAbsIndex);
        }

        int bitIndex = bitAbsIndex % 8;
        int shift = (7 - bitIndex);
        int bitMask = 0b1 << shift;
        byte currentByte = bitfield[byteIndex];
        bitfield[byteIndex] = (byte) (currentByte | bitMask);
    }

    /**
     * Gets n-th bit in the bitfield
     * (which is considered a continuous bit array of bits, indexed starting with 0 from left to right)
     */
    private static int getBit(byte[] bitfield, int bitAbsIndex) {

        int byteIndex = (int) (bitAbsIndex / 8d);
        if (byteIndex >= bitfield.length) {
            throw new BtException("bit index is too large: " + bitAbsIndex);
        }

        int bitIndex = bitAbsIndex % 8;
        int shift = (7 - bitIndex);
        int bitMask = 0b1 << shift ;
        return (bitfield[byteIndex] & bitMask) >> shift;
    }

    private static class PieceSelectorHelper {

        private static final int PIECE_SELECTION_LIMIT = 25;

        private Map<PeerConnection, byte[]> peerBitfields;

        private PieceStats stats;
        private PieceSelector selector;
        private Predicate<Integer> validator;

        private int bitfieldLength;

        PieceSelectorHelper(PieceStats stats, PieceSelector selector,
                            Predicate<Integer> validator, int bitfieldLength) {
            this.stats = stats;
            this.selector = selector;
            this.validator = validator;
            peerBitfields = new HashMap<>();

            this.bitfieldLength = bitfieldLength;
        }

        void addPeerBitfield(PeerConnection peer, byte[] peerBitfield) {
            peerBitfields.put(peer, peerBitfield);
            stats.addBitfield(peerBitfield);
        }

        void addPeerPiece(PeerConnection peer, Integer pieceIndex) {
            byte[] peerBitfield = peerBitfields.get(peer);
            if (peerBitfield == null) {
                peerBitfield = new byte[bitfieldLength];
                peerBitfields.put(peer, peerBitfield);
            }

            setBit(peerBitfield, pieceIndex);
            stats.addPiece(pieceIndex);
        }

        Optional<Integer> selectPieceForPeer(PeerConnection peer) {

            byte[] peerBitfield = peerBitfields.get(peer);
            if (peerBitfield != null) {
                Integer[] pieces = getNextPieces();
                for (Integer piece : pieces) {
                    if (getBit(peerBitfield, piece) != 0) {
                        return Optional.of(piece);
                    }
                }
            }
            return Optional.empty();
        }

        Integer[] getNextPieces() {

            // update the aggregate bitfield for disconnected peers
            Iterator<Map.Entry<PeerConnection, byte[]>> iter = peerBitfields.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<PeerConnection, byte[]> peerBitfield = iter.next();
                if (peerBitfield.getKey().isClosed()) {
                    stats.removeBitfield(peerBitfield.getValue());
                    iter.remove();
                }
            }
            // TODO: more intelligent piece selection and piece-to-peer assignment algorithms

            // first option is to somehow cache the results until something is changed
            // this is rather tricky because even 1 ignored change from a peer (e.g. a newly acquired piece)
            // can result in that the worker assigned to that peer won't start working immediately
            // and instead will be waiting for other (irrelevant) changes

            // -- OR maybe there's a need for some "dynamic" data structure (heap-based?)
            //    that will update itself after each change?
            //    this actually should happen way less frequently than the worker calls for the next piece
            return recalculateNextPieces();
        }

        Integer[] recalculateNextPieces() {
            return selector.getNextPieces(stats, PIECE_SELECTION_LIMIT,
                    pieceIndex -> (pieceIndex < stats.size()) && validator.test(pieceIndex));
        }
    }

    private static class PieceStats implements IPieceStats {

        private int[] pieceTotals;
        private long changesCount;

        PieceStats(int pieceCount) {
            this.pieceTotals = new int[pieceCount];
        }

        void addBitfield(byte[] bitfield) {
            for (int i = 0; i < pieceTotals.length; i++) {
                if (getBit(bitfield, i) == 1) {
                    pieceTotals[i]++;
                    changesCount++;
                }
            }
        }

        void removeBitfield(byte[] bitfield) {
            for (int i = 0; i < pieceTotals.length; i++) {
                if (getBit(bitfield, i) == 1) {
                    pieceTotals[i]--;
                    changesCount--;
                }
            }
        }

        void addPiece(Integer pieceIndex) {
            pieceTotals[pieceIndex]++;
            changesCount++;
        }

        long getChangesCount() {
            return changesCount;
        }

        void resetChangesCount() {
            changesCount = 0;
        }

        @Override
        public int getCount(int pieceIndex) {
            return pieceTotals[pieceIndex];
        }

        @Override
        public int size() {
            return pieceTotals.length;
        }
    }

    private static class Assignments {

        private Map<PeerConnection, Integer> assignedPieces;
        private Map<Integer, PeerConnection> assignedPeers;

        Assignments() {
            assignedPieces = new HashMap<>();
            assignedPeers = new HashMap<>();
        }

        void assignPiece(PeerConnection peer, Integer pieceIndex) {
            Iterator<Map.Entry<PeerConnection, Integer>> iter = assignedPieces.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<PeerConnection, Integer> entry = iter.next();
                if (entry.getKey().isClosed()) {
                    assignedPeers.remove(entry.getValue());
                    iter.remove();
                }
            }

            assignedPieces.put(peer, pieceIndex);
            assignedPeers.put(pieceIndex, peer);
        }

        Optional<Integer> getAssignedPiece(PeerConnection peer) {
            return Optional.ofNullable(assignedPieces.get(peer));
        }

        Optional<PeerConnection> getAssignee(Integer pieceIndex) {
            return Optional.ofNullable(assignedPeers.get(pieceIndex));
        }

        void removeAssignee(Integer pieceIndex) {
            PeerConnection assignee = assignedPeers.remove(pieceIndex);
            assignedPieces.remove(assignee);
        }
    }
}
