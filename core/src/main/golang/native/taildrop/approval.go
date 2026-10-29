package taildrop

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"time"
)

const approvalTimeout = 2 * time.Minute

var (
	errReceiveRejected = errors.New("file reception declined")
	errApprovalExpired = errors.New("file reception approval expired")
	errApprovalBusy    = errors.New("too many pending file requests")
	errApprovalClosed  = errors.New("file reception stopped")
)

type fileOffer struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Sender string `json:"sender"`
}

type pendingOffer struct {
	fileOffer
	key      incomingFileKey
	decision chan bool
	decided  bool
	expires  time.Time
}

// Approval gates only PeerAPI PUTs. It never reads their bodies or opens files.
func (m *manager) awaitApproval(ctx context.Context, sender clientID, senderName, name string, size int64, timeout time.Duration) error {
	if !m.opts.RequireApproval {
		return nil
	}
	var token [16]byte
	if _, err := rand.Read(token[:]); err != nil {
		return err
	}
	if senderName == "" {
		senderName = string(sender)
	}
	offer := &pendingOffer{fileOffer: fileOffer{hex.EncodeToString(token[:]), name, size, senderName},
		key: incomingFileKey{sender, name}, decision: make(chan bool, 1), expires: time.Now().Add(timeout)}
	m.approvalMu.Lock()
	if len(m.offers) >= 32 {
		m.approvalMu.Unlock()
		return errApprovalBusy
	}
	for _, other := range m.offers {
		if other.key == offer.key {
			m.approvalMu.Unlock()
			return ErrFileExists
		}
	}
	m.offers[offer.ID] = offer
	m.approvalMu.Unlock()
	m.opts.SendFileNotify()
	defer func() {
		m.approvalMu.Lock()
		delete(m.offers, offer.ID)
		m.approvalMu.Unlock()
		m.opts.SendFileNotify()
	}()
	timer := time.NewTimer(time.Until(offer.expires))
	defer timer.Stop()
	select {
	case accept := <-offer.decision:
		// A stale approval must not revive a cancelled request or stopped node.
		select {
		case <-m.approvalStop:
			return errApprovalClosed
		default:
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if !time.Now().Before(offer.expires) {
			return errApprovalExpired
		}
		if !accept {
			return errReceiveRejected
		}
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-m.approvalStop:
		return errApprovalClosed
	case <-timer.C:
		return errApprovalExpired
	}
}

func (m *manager) pendingOffers() []fileOffer {
	result := []fileOffer{}
	if m == nil {
		return result
	}
	m.approvalMu.Lock()
	defer m.approvalMu.Unlock()
	for _, offer := range m.offers {
		if !offer.decided {
			result = append(result, offer.fileOffer)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func (m *manager) decideOffer(id string, accept bool) error {
	if m == nil {
		return errApprovalClosed
	}
	m.approvalMu.Lock()
	defer m.approvalMu.Unlock()
	offer := m.offers[id]
	if offer == nil || offer.decided || !time.Now().Before(offer.expires) {
		return errors.New("file request expired or already answered")
	}
	select {
	case <-m.approvalStop:
		return errApprovalClosed
	default:
	}
	offer.decided = true
	offer.decision <- accept
	return nil
}
